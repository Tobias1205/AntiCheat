package nl.shieldguard.protection;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;

import java.util.Arrays;

/**
 * Past uitgaande packets per speler aan:
 *  1. alles diep onder het oppervlak EN diep onder de speler wordt deepslate/stone (anti-freecam)
 *  2. ALLE erts en storage-blokken worden stone/deepslate, tenzij de speler ze dichtbij en in zicht heeft
 *     (anti-xray, anti-storage-ESP). Het tonen gebeurt in {@link Revealer}.
 */
public final class ChunkPacketListener implements PacketListener {

    /** Binnen zoveel blokken worden nieuw geplaatste/veranderde blokken meteen echt getoond. */
    private static final int NEAR = 6;

    private final MaskManager masks;

    public ChunkPacketListener(MaskManager masks) {
        this.masks = masks;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        try {
            dispatch(event);
        } catch (Throwable t) {
            masks.reportError("packet " + event.getPacketType(), t);
        }
    }

    private void dispatch(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            handleChunk(event);
        } else if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
            User user = event.getUser();
            if (user == null) return;
            MaskManager.State st = masks.state(user.getUUID());
            if (st == null) return;
            WrapperPlayServerUnloadChunk w = new WrapperPlayServerUnloadChunk(event);
            st.sent.remove(MaskManager.key(w.getChunkX(), w.getChunkZ()));
        } else if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            handleBlockChange(event);
        } else if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            handleMultiBlockChange(event);
        }
    }

    // ------------------------------------------------------------ chunk data

    private void handleChunk(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null) return;
        MaskManager.State st = masks.state(user.getUUID());
        if (st == null || !masks.active(st)) return;

        masks.chunksSeen.incrementAndGet();
        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        Column column = wrapper.getColumn();
        long chunkKey = MaskManager.key(column.getX(), column.getZ());

        int cutoff = masks.cutoff(st);
        boolean hide = masks.blocksHidden(st);
        if (cutoff == MaskManager.NO_MASK && !hide) {
            st.sent.put(chunkKey, MaskManager.NO_MASK);
            return;
        }

        ClientVersion version = user.getClientVersion();
        Ids ids = masks.ids(version);
        BaseChunk[] sections = column.getChunks();
        int minY = st.minY;
        int margin = masks.surfaceMargin();
        boolean hideExposed = masks.hideExposedOres();
        int baseX = column.getX() * 16;
        int baseZ = column.getZ() * 16;

        // Oppervlakte per kolom bepalen vóórdat we iets aanpassen.
        int[] surface = cutoff != MaskManager.NO_MASK ? surface(sections, minY, ids) : null;
        long filled = 0, ores = 0, storage = 0;

        for (int i = 0; i < sections.length; i++) {
            BaseChunk section = sections[i];
            if (section == null) continue;
            int y0 = minY + i * 16;
            boolean belowCutoff = cutoff != MaskManager.NO_MASK && y0 < cutoff;
            if (!belowCutoff && (!hide || section.isEmpty())) continue;

            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    // Alles onder 'limit' is "diep onder de grond" en wordt gevuld.
                    int limit = Integer.MIN_VALUE;
                    if (belowCutoff) {
                        limit = Math.min(cutoff, surface[lz * 16 + lx] - margin);
                    }
                    for (int ly = 0; ly < 16; ly++) {
                        int y = y0 + ly;
                        if (y < limit) {
                            section.set(version, lx, ly, lz, ids.fillFor(y));
                            filled++;
                            continue;
                        }
                        if (!hide) continue;
                        byte cls = ids.classify(section.getBlockId(lx, ly, lz));
                        if (cls != Ids.ORE && cls != Ids.STORAGE) continue;
                        if (cls == Ids.ORE && !hideExposed && exposed(sections, i, lx, ly, lz, ids)) continue;
                        if (st.revealed.containsKey(MaskManager.pos(baseX + lx, y, baseZ + lz))) continue;
                        section.set(version, lx, ly, lz, ids.fillFor(y));
                        if (cls == Ids.ORE) ores++;
                        else storage++;
                    }
                }
            }
        }

        masks.chunksModified.incrementAndGet();
        masks.blocksFilled.addAndGet(filled);
        masks.oresHidden.addAndGet(ores);
        masks.storageHidden.addAndGet(storage);
        st.sent.put(chunkKey, cutoff);
        event.markForReEncode(true);
    }

    /** Y van het hoogste niet-open blok per kolom (index z*16+x), of minY-1 als de kolom leeg is. */
    private static int[] surface(BaseChunk[] sections, int minY, Ids ids) {
        int[] result = new int[256];
        Arrays.fill(result, minY - 1);
        boolean[] done = new boolean[256];
        int remaining = 256;
        for (int i = sections.length - 1; i >= 0 && remaining > 0; i--) {
            BaseChunk section = sections[i];
            if (section == null || section.isEmpty()) continue;
            int y0 = minY + i * 16;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = z * 16 + x;
                    if (done[idx]) continue;
                    for (int y = 15; y >= 0; y--) {
                        if (ids.classify(section.getBlockId(x, y, z)) != Ids.OPEN) {
                            result[idx] = y0 + y;
                            done[idx] = true;
                            remaining--;
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    // ----------------------------------------------------------- block changes

    private void handleBlockChange(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null) return;
        MaskManager.State st = masks.state(user.getUUID());
        if (st == null || !masks.blocksHidden(st)) return;

        WrapperPlayServerBlockChange w = new WrapperPlayServerBlockChange(event);
        Ids ids = masks.ids(user.getClientVersion());
        byte cls = ids.classify(w.getBlockId());
        if (cls != Ids.ORE && cls != Ids.STORAGE) return;

        Vector3i p = w.getBlockPosition();
        if (allow(st, p.getX(), p.getY(), p.getZ())) return;

        w.setBlockState(WrappedBlockState.getByGlobalId(user.getClientVersion(), ids.fillFor(p.getY())));
        event.markForReEncode(true);
    }

    private void handleMultiBlockChange(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null) return;
        MaskManager.State st = masks.state(user.getUUID());
        if (st == null || !masks.blocksHidden(st)) return;

        WrapperPlayServerMultiBlockChange w = new WrapperPlayServerMultiBlockChange(event);
        Ids ids = masks.ids(user.getClientVersion());
        WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks = w.getBlocks();
        boolean changed = false;
        for (WrapperPlayServerMultiBlockChange.EncodedBlock b : blocks) {
            byte cls = ids.classify(b.getBlockId());
            if (cls != Ids.ORE && cls != Ids.STORAGE) continue;
            if (allow(st, b.getX(), b.getY(), b.getZ())) continue;
            b.setBlockId(ids.fillFor(b.getY()));
            changed = true;
        }
        if (changed) {
            w.setBlocks(blocks);
            event.markForReEncode(true);
        }
    }

    /** Mag dit blok echt getoond worden (al onthuld of vlak bij de speler)? Zo ja, dan wordt het onthuld. */
    private static boolean allow(MaskManager.State st, int x, int y, int z) {
        long key = MaskManager.pos(x, y, z);
        if (st.revealed.containsKey(key)) return true;
        if (Math.abs(x - st.x) <= NEAR && Math.abs(y - st.y) <= NEAR && Math.abs(z - st.z) <= NEAR) {
            st.revealed.put(key, Boolean.TRUE);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------- hulpjes

    /** (Alleen als hide-exposed-ores uit staat) erts blijft zichtbaar als een buur lucht/water/lava is. */
    private static boolean exposed(BaseChunk[] sections, int index, int x, int y, int z, Ids ids) {
        if (x == 0 || x == 15 || z == 0 || z == 15) return true;
        return open(sections, index, x, y + 1, z, ids)
                || open(sections, index, x, y - 1, z, ids)
                || open(sections, index, x + 1, y, z, ids)
                || open(sections, index, x - 1, y, z, ids)
                || open(sections, index, x, y, z + 1, ids)
                || open(sections, index, x, y, z - 1, ids);
    }

    private static boolean open(BaseChunk[] sections, int index, int x, int y, int z, Ids ids) {
        int si = index;
        if (y < 0) {
            si--;
            y += 16;
        } else if (y > 15) {
            si++;
            y -= 16;
        }
        if (si < 0 || si >= sections.length || sections[si] == null) return true;
        return ids.classify(sections[si].getBlockId(x, y, z)) == Ids.OPEN;
    }
}
