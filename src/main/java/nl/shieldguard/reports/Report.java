package nl.shieldguard.reports;

import java.util.UUID;

public final class Report {
    public final int id;
    public final UUID reporter;
    public final String reporterName;
    public final UUID target;
    public final String targetName;
    public final String reason;
    public final long time;
    public final String world;
    public final double x, y, z;

    public boolean closed;
    public String handledBy = "";
    public String note = "";

    public Report(int id, UUID reporter, String reporterName, UUID target, String targetName,
                  String reason, long time, String world, double x, double y, double z) {
        this.id = id;
        this.reporter = reporter;
        this.reporterName = reporterName;
        this.target = target;
        this.targetName = targetName;
        this.reason = reason;
        this.time = time;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
