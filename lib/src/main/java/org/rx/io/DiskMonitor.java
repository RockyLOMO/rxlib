package org.rx.io;

import lombok.extern.slf4j.Slf4j;
import org.rx.bean.Decimal;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.core.TimeoutFlag;
import org.rx.util.function.Action;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.rx.core.Extends.asyncContinue;
import static org.rx.core.Extends.eachQuietly;

@Slf4j
public class DiskMonitor {
    public static final DiskMonitor INSTANCE = new DiskMonitor();
    final Map<Integer, Set<Action>> fns = new ConcurrentSkipListMap<>();

    private DiskMonitor() {
        Tasks.setTimeout(() -> {
            File root = new File("/");
            long totalSpace = root.getTotalSpace();
            int up = Decimal.valueOf((double) (totalSpace - root.getUsableSpace()) / totalSpace).toPercentInt();
            eachQuietly(fns.entrySet(), entry -> {
                if (entry.getKey() > up) {
                    asyncContinue(false);
                    return;
                }
                log.debug("DiskMonitor Used={}% Threshold={} -> {}", up, entry.getKey(), entry.getValue().size());
                eachQuietly(entry.getValue(), Action::invoke);
            });
        }, RxConfig.INSTANCE.getDisk().getMonitorPeriod(), null, TimeoutFlag.PERIOD.flags());
    }

    public void register(int usedPercent, Action callback) {
        fns.computeIfAbsent(usedPercent, k -> ConcurrentHashMap.newKeySet()).add(callback);
    }
}
