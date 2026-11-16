package labs.design.tech_lead_review_mentoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把团队对合并请求的几条约定写成检查，在评审人打开它之前先跑一遍。
 * 机器查得了的交给机器，评审人的时间留给设计和正确性。
 * 运行：mvn -q -pl design/tech-lead-review-mentoring compile exec:java -Dexec.mainClass=labs.design.tech_lead_review_mentoring.PrCheck
 *       -Dexec.args="design/tech-lead-review-mentoring/prs/pr-101.txt design/tech-lead-review-mentoring/prs/pr-102.txt design/tech-lead-review-mentoring/prs/pr-103.txt"
 */
public class PrCheck {
    static final int MAX_LINES = 400;

    record Change(int added, int deleted, String path) {
        boolean isTest() { return path.contains("src/test/"); }
        boolean isMigration() { return path.contains("db/migration/"); }
        int lines() { return added + deleted; }
    }

    public static void main(String[] args) throws IOException {
        for (String file : args) {
            List<String> lines = Files.readAllLines(Path.of(file));
            String title = lines.get(0).replaceFirst("^标题: ", "");
            List<String> problems = check(title, lines);
            System.out.println((problems.isEmpty() ? "✓ " : "✗ ") + Path.of(file).getFileName().toString().replace(".txt", "") + "  " + title);
            problems.forEach(p -> System.out.println("    - " + p));
        }
    }

    static List<String> check(String title, List<String> lines) {
        List<String> problems = new ArrayList<>();
        boolean refactor = title.startsWith("refactor:");

        for (String sec : new String[]{"背景", "怎么验证", "回滚"})
            if (String.join("", section(lines, sec)).isBlank()) problems.add("描述里没有“" + sec + "”");

        List<Change> changes = new ArrayList<>();
        for (String l : section(lines, "改动")) {
            String[] p = l.split("\t");
            if (p.length == 3) changes.add(new Change(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[2]));
        }
        int code = changes.stream().filter(c -> !c.isTest() && !c.isMigration()).mapToInt(Change::lines).sum();
        if (code > MAX_LINES) problems.add("非测试代码改了 " + code + " 行，超过 " + MAX_LINES + " 行：评审的人看不完，拆开提");

        if (refactor) {
            changes.stream().filter(Change::isTest).forEach(c -> problems.add("标题是 refactor，却改了测试 " + shortName(c.path()) + "：重构不改行为，测试就不该动"));
            String body = String.join("", lines);
            for (String word : new String[]{"顺便", "顺手", "修了"})
                if (body.contains(word)) { problems.add("标题是 refactor，描述里出现“" + word + "”：夹带了行为变化的话，单独提一个"); break; }
        } else {
            changes.stream().filter(c -> c.isTest() && c.path().contains("Characterization") && c.deleted() > 0)
                .forEach(c -> problems.add("改了特征测试 " + shortName(c.path()) + " 的已有内容：老行为的标准答案不能跟着新功能改"));
        }
        if (changes.stream().anyMatch(Change::isMigration) && changes.stream().anyMatch(c -> !c.isMigration()))
            problems.add("表结构变更和代码混在一起：迁移脚本单独提，先上线，再提用到新列的代码");
        return problems;
    }

    static List<String> section(List<String> lines, String name) {
        int start = lines.indexOf("## " + name);
        List<String> out = new ArrayList<>();
        if (start < 0) return out;
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) out.add(lines.get(i));
        return out;
    }

    static String shortName(String path) { return path.substring(path.lastIndexOf('/') + 1); }
}
