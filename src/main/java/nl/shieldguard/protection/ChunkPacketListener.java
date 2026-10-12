package nl.shieldguard.protection;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Past uitgaande chunk-packets per speler aan:
 *  1. alles onder de diepte-grens wordt deepslate/stone (anti-freecam)
 *  2. ingesloten erts wordt stone/deepslate (anti-xray)
 */
public final class ChunkPacketListener implements PacketListener {

    private final MaskManager masks;

    public ChunkPacketListener(MaskManager masks) {
        this.masks = masks;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            handleChunk(event);
        } else if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
            User user = event.getUser();
            if (user == null) return;
            MaskManager.State st = masks.state(user.getUUID());
            if (st == null) return;
            WrapperPlayServerUnloadChunk w = new WrapperPlayServerUnloadChunk(event);
            st.sent.remove(MaskManager.key(w.getChunkX(), w.getChunkZ()));
        }
    }

    private void handleChunk(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null) return;
        MaskManager.State st = masks.state(user.getUUID());
        if (st == null || !st.enabled || st.bypass) return;

        WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
        Column column = wrapper.getColumn();
        long key = MaskManager.key(column.getX(), column.getZ());

        int cutoff = masks.cutoff(st);
        boolean hideOres = masks.oresHidden(st);
        if (cutoff == MaskManager.NO_MASK && !hideOres) {
            st.sent.put(key, MaskManager.NO_MASK);
            return;
        }

        ClientVersion version = user.getClientVersion();
        Ids ids = masks.ids(version);
        BaseChunk[] sections = column.getChunks();
        int minY = st.minY;

        for (int i = 0; i < sections.length; i++) {
            BaseChunk section = sections[i];
            if (section == null) continue;
            int y0 = minY + i * 16;
            if (cutoff != MaskManager.NO_MASK && y0 + 16 <= cutoff) {
                fill(section, version, ids.fillFor(y0));
            } else if (hideOres && !section.isEmpty()) {
                hideOres(sections, i, y0, ids, version);
            }
        }

        // Tile entities (kisten, spawners, ...) onder de grens mogen ook niet verstuurd worden
        TileEntity[] tiles = column.getTileEntities();
        if (cutoff != MaskManager.NO_MASK && tiles != null && tiles.length > 0) {
            List<TileEntity> keep = new ArrayList<>(tiles.length);
            for (TileEntity te : tiles) {
                if (te.getY() >= cutoff) keep.add(te);
            }
            if (keep.size() != tiles.length) {
                Column filtered = new Column(column.getX(), column.getZ(), column.isFullChunk(),
                        column.getChunks(), keep.toArray(new TileEntity[0]), column.getHeightMaps());
                wrapper.setColumn(filtered);
            }
        }

        st.sent.put(key, cutoff);
        event.markForReEncode(true);
    }

    private static void fill(BaseChunk section, ClientVersion version, int blockId) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    section.set(version, x, y, z, blockId);
                }
            }
        }
    }

    private static void hideOres(BaseChunk[] sections, int index, int y0, Ids ids, ClientVersion version) {
        BaseChunk section = sections[index];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = section.getBlockId(x, y, z);
                    if (ids.classify(id) != Ids.ORE) continue;
                    if (exposed(sections, index, x, y, z, ids)) continue;
                    section.set(version, x, y, z, ids.fillFor(y0 + y));
                }
            }
        }
    }

    /** Erts blijft zichtbaar als een buur lucht/water/lava is (of buiten deze chunk valt). */
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
