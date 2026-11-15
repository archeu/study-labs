package labs.observability.postmortem_review;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复盘第一步：从原始记录拼时间线，不凭记忆。
 * 输入是 Prometheus 那页 run.sh 打出的事件（负载统计、告警状态变化、webhook 通知、注入时刻），
 * 输出一张 Markdown 时间线，以及几个从时间线算出来的数：发现用了多久、恢复用了多久、通知比出错晚多少。
 *
 * 用法：TimelineBuilder [事件文件]，默认 sample/prometheus-alerting-events.txt
 */
public class TimelineBuilder {

    record Event(LocalTime at, String kind, String text) { }

    private static final Pattern LINE = Pattern.compile("^(\\d{2}:\\d{2}(?::\\d{2})?) (\\S+)\\s+(.*)$");
    private static final Pattern LOAD = Pattern.compile("200=(\\d+) 5xx=(\\d+)");

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args.length > 0 ? args[0] : "sample/prometheus-alerting-events.txt");
        List<Event> events = new ArrayList<>();
        for (String raw : Files.readAllLines(in)) {
            Matcher m = LINE.matcher(raw.strip());
            if (m.matches()) events.add(new Event(LocalTime.parse(m.group(1)), m.group(2), m.group(3).strip()));
        }

        LocalTime inject = null, firstErrWindow = null, recovered = null, firstNotify = null, firstPending = null;
        boolean erring = false;
        List<String[]> rows = new ArrayList<>();
        for (Event e : events) {
            switch (e.kind()) {
                case "T0" -> { inject = e.at(); rows.add(row(e.at(), "变更 / 故障", "注入：" + e.text().replace("注入：", ""))); }
                case "load" -> {
                    Matcher m = LOAD.matcher(e.text());
                    if (!m.find()) break;
                    int ok = Integer.parseInt(m.group(1)), err = Integer.parseInt(m.group(2));
                    if (err > 0 && !erring) {
                        erring = true; firstErrWindow = e.at();
                        rows.add(row(e.at(), "用户影响", "这 10 秒里第一次出现 5xx：" + err + " 次（共 " + (ok + err) + " 次）"));
                    } else if (err == 0 && erring && recovered == null) {
                        recovered = e.at();
                        rows.add(row(e.at(), "用户影响", "这 10 秒里 5xx 归零，恢复"));
                    }
                }
                case "rule" -> {
                    rows.add(row(e.at(), "告警规则", e.text().replaceAll("\\s+", " ")));
                    if (firstPending == null && e.text().contains("-> pending")) firstPending = e.at();
                }
                case "webhook" -> {
                    rows.add(row(e.at(), "通知到人", e.text().replaceAll("\\s+", " ")));
                    if (firstNotify == null && e.text().contains("firing")) firstNotify = e.at();
                }
                default -> { }
            }
        }

        System.out.println("| 时间 | 类别 | 发生了什么 |");
        System.out.println("|---|---|---|");
        for (String[] r : rows) System.out.println("| " + r[0] + " | " + r[1] + " | " + r[2] + " |");
        System.out.println();
        if (inject == null) { System.out.println("没有找到注入时刻（T0），算不了时长"); return; }
        System.out.println("从时间线算出来的数（以注入时刻 " + inject + " 为起点）：");
        print("用户第一次受影响（所在 10 秒窗口的末尾）", inject, firstErrWindow);
        print("第一条告警规则进入 pending", inject, firstPending);
        print("第一条通知到人（发现）", inject, firstNotify);
        print("5xx 归零（恢复）", inject, recovered);
        if (firstNotify != null && recovered != null) {
            long notifyToRecover = Duration.between(firstNotify, recovered).toSeconds();
            System.out.printf("通知到恢复：%d 秒。这次没有人处理，是“对账”自己结束了；真实事故里这一段是排查加止血。%n", notifyToRecover);
        }
    }

    private static String[] row(LocalTime t, String kind, String text) {
        return new String[] { t.toString(), kind, text };
    }

    private static void print(String what, LocalTime from, LocalTime to) {
        if (to == null) { System.out.println("- " + what + "：没有"); return; }
        System.out.printf("- %s：+%d 秒（%s）%n", what, Duration.between(from, to).toSeconds(), to);
    }
}
