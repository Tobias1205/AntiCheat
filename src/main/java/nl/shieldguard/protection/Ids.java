package nl.shieldguard.protection;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;

import java.util.Set;

/** Per client-versie: block-id's classificeren en de vul-blokken kennen. */
public final class Ids {

    public static final byte SOLID = 1;
    public static final byte ORE = 2;
    public static final byte OPEN = 3;
    public static final byte STORAGE = 4;

    private final ClientVersion version;
    private final Set<String> ores;
    private final Set<String> storage;
    private final byte[] classes = new byte[1 << 17];

    public final int stone;
    public final int deepslate;

    public Ids(ClientVersion version, Set<String> ores, Set<String> storage) {
        this.version = version;
        this.ores = ores;
        this.storage = storage;
        this.stone = StateTypes.STONE.createBlockState(version).getGlobalId();
        this.deepslate = StateTypes.DEEPSLATE.createBlockState(version).getGlobalId();
    }

    public byte classify(int id) {
        if (id < 0 || id >= classes.length) return SOLID;
        byte c = classes[id];
        if (c == 0) {
            c = compute(id);
            classes[id] = c;
        }
        return c;
    }

    private byte compute(int id) {
        WrappedBlockState state = WrappedBlockState.getByGlobalId(version, id);
        StateType type = state.getType();
        String name = MaskManager.normalize(type.getName());
        if (ores.contains(name)) return ORE;
        if (storage.contains(name)) return STORAGE;
        if (!type.isSolid()) return OPEN;
        return SOLID;
    }

    public int fillFor(int worldY) {
        return worldY < 0 ? deepslate : stone;
    }
}
