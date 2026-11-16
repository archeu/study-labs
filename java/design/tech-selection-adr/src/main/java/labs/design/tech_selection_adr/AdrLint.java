package labs.design.tech_selection_adr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把团队对 ADR 的约定写成检查，放进流水线：格式不对的 ADR 合不进主干。
 * 运行：mvn -q -pl design/tech-selection-adr compile exec:java
 *       -Dexec.mainClass=labs.design.tech_selection_adr.AdrLint -Dexec.args="design/tech-selection-adr/docs/adr design/tech-selection-adr/docs/adr-bad"
 */
public class AdrLint {
    static final Pattern FILE = Pattern.compile("(\\d{4})-[a-z0-9]+(-[a-z0-9]+)*\\.md");
    static final Pattern TITLE = Pattern.compile("# ADR-(\\d{4}) .+");
    static final Pattern STATUS = Pattern.compile("- 状态：(.+)");
    static final Pattern SUPERSEDED = Pattern.compile("已被 ADR-(\\d{4}) 取代");
    static final Pattern REF = Pattern.compile("ADR-(\\d{4})");
    static final String[] SECTIONS = {"背景", "决定", "放弃的方案", "后果", "重新看的条件"};
    static final int MAX_LINES = 60;

    public static void main(String[] args) throws IOException {
        Map<String, Path> all = new TreeMap<>();                 // 编号 → 文件
        for (String dir : args) {
            try (Stream<Path> files = Files.list(Path.of(dir))) {
                files.filter(p -> FILE.matcher(p.getFileName().toString()).matches())
                     .forEach(p -> all.put(p.getFileName().toString().substring(0, 4), p));
            }
        }
        int bad = 0;
        for (Map.Entry<String, Path> e : all.entrySet()) {
            List<String> problems = check(e.getKey(), e.getValue(), all);
            System.out.println((problems.isEmpty() ? "✓ " : "✗ ") + e.getValue().getParent().getFileName() + "/" + e.getValue().getFileName());
            problems.forEach(p -> System.out.println("    - " + p));
            if (!problems.isEmpty()) bad++;
        }
        List<String> gaps = new ArrayList<>();
        int first = Integer.parseInt(((TreeMap<String, Path>) all).firstKey()), last = Integer.parseInt(((TreeMap<String, Path>) all).lastKey());
        for (int n = first; n <= last; n++) if (!all.containsKey(String.format("%04d", n))) gaps.add(String.format("%04d", n));
        if (!gaps.isEmpty()) System.out.println("✗ 编号不连续，缺 " + gaps);
        System.out.println("\n" + all.size() + " 条 ADR，" + (all.size() - bad) + " 条通过，" + bad + " 条有问题" + (gaps.isEmpty() ? "" : "，编号缺 " + gaps.size() + " 个"));
    }

    static List<String> check(String no, Path file, Map<String, Path> all) throws IOException {
        List<String> lines = Files.readAllLines(file), problems = new ArrayList<>();
        Matcher t = TITLE.matcher(lines.get(0));
        if (!t.matches()) problems.add("第一行应该是“# ADR-NNNN 决定本身”");
        else if (!t.group(1).equals(no)) problems.add("标题里的编号 " + t.group(1) + " 和文件名 " + no + " 对不上");

        String status = lines.stream().map(STATUS::matcher).filter(Matcher::matches).map(m -> m.group(1).trim()).findFirst().orElse(null);
        if (status == null) problems.add("没有“- 状态：”这一行");
        else {
            Matcher s = SUPERSEDED.matcher(status);
            if (s.matches()) { if (!all.containsKey(s.group(1))) problems.add("说被 ADR-" + s.group(1) + " 取代，但找不到那一条"); }
            else if (!List.of("提议中", "已接受", "已废弃").contains(status)) problems.add("状态“" + status + "”不在约定里：提议中、已接受、已废弃、已被 ADR-NNNN 取代");
        }
        for (String sec : SECTIONS) {
            List<String> body = section(lines, sec);
            if (body == null) { problems.add("缺“## " + sec + "”一节"); continue; }
            String text = String.join("", body).trim();
            if (text.isEmpty()) problems.add("“" + sec + "”一节是空的");
            if (sec.equals("决定") && !text.startsWith("我们")) problems.add("“决定”要用“我们……”开头，写成一句完整的话");
            if (sec.equals("放弃的方案"))
                body.stream().filter(l -> l.startsWith("- ") && !l.contains("输在")).forEach(l -> problems.add("放弃的方案没写输在哪：" + l.substring(2)));
            if (sec.equals("重新看的条件") && (text.contains("暂无") || text.equals("无"))) problems.add("“重新看的条件”不能写暂无");
        }
        for (String line : lines.subList(1, lines.size())) {
            Matcher r = REF.matcher(line);
            while (r.find()) if (!all.containsKey(r.group(1))) problems.add("正文提到 ADR-" + r.group(1) + "，但找不到那一条");
        }
        if (lines.size() > MAX_LINES) problems.add("有 " + lines.size() + " 行，超过 " + MAX_LINES + " 行：一条 ADR 一到两页");
        return problems;
    }

    /** 取出“## 名字”到下一个“## ”之间的行；没有这一节返回 null。 */
    static List<String> section(List<String> lines, String name) {
        int start = lines.indexOf("## " + name);
        if (start < 0) return null;
        List<String> out = new ArrayList<>();
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) out.add(lines.get(i));
        return out;
    }
}
