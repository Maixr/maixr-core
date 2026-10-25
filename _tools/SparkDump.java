import me.lucko.spark.proto.SparkSamplerProtos.SamplerData;
import me.lucko.spark.proto.SparkSamplerProtos.StackTraceNode;
import me.lucko.spark.proto.SparkSamplerProtos.ThreadNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 离线解析 spark SamplerData：childrenRefs = ThreadNode.children 扁平池下标（实测确认）。
 *  输出：各线程根调用分布 + 指定线程的按类自耗时 TOP（供生成性能决策表归因）。 */
public class SparkDump {

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        String threadFilter = args.length > 1 ? args[1] : "Server thread";
        SamplerData s = SamplerData.parseFrom(data);
        System.out.println("# windows=" + s.getTimeWindowsCount()
                + " ticks=" + s.getMetadata().getNumberOfTicks()
                + " interval=" + s.getMetadata().getInterval());
        for (ThreadNode tn : s.getThreadsList()) {
            if (threadFilter.equals("__LIST__")) {
                System.out.printf("线程 %-28s 池=%d 总时长=%.1f%n",
                        tn.getName(), tn.getChildrenCount(),
                        tn.getTimesList().stream().mapToDouble(Double::doubleValue).sum());
                continue;
            }
            List<StackTraceNode> pool = tn.getChildrenList();
            Map<Integer, StackTraceNode> byIdx = new HashMap<>();
            for (StackTraceNode n : pool) {
                byIdx.put(byIdx.size(), n);
            }
            double threadTotal = tn.getTimesList().stream().mapToDouble(Double::doubleValue).sum();
            System.out.println("\n===== 线程 [" + tn.getName() + "] 池=" + pool.size()
                    + " 总时长=" + String.format("%.1f", threadTotal) + " =====");
            // 根调用分布
            double[] rootSelf = new double[pool.size()];
            Map<String, Double> rootByName = new TreeMap<>();
            for (int rootIdx : tn.getChildrenRefsList()) {
                StackTraceNode r = byIdx.get(rootIdx);
                double t = r.getTimesList().stream().mapToDouble(Double::doubleValue).sum();
                rootByName.put(r.getClassName() + "." + r.getMethodName(), t);
            }
            rootByName.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .forEach(e -> System.out.printf("  root %10.1f  %s%n", e.getValue(), e.getKey()));

            // 重调用链（火焰图文本版）：阈值 = 线程总时长 1%
            System.out.println("-- 重调用链（total ≥ 线程总时长 1%）--");
            for (int rootIdx : tn.getChildrenRefsList()) {
                printHeavyChain(byIdx, rootIdx, threadTotal * 0.01, 0, new StringBuilder("  "));
            }

            if (!tn.getName().equals(threadFilter)) {
                continue;
            }
            // 按类自耗时聚合
            Map<String, double[]> byClass = new HashMap<>();
            for (int rootIdx : tn.getChildrenRefsList()) {
                dfs(byIdx, rootIdx, byClass);
            }
            System.out.println("-- 按类自耗时 TOP30（相对线程总时长的 %）--");
            byClass.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(30)
                    .forEach(e -> System.out.printf("  %6.2f%%  %s%n",
                            threadTotal > 0 ? 100.0 * e.getValue()[0] / threadTotal : 0.0,
                            e.getKey()));
            // 按顶层包聚合
            Map<String, Double> byPkg = new TreeMap<>();
            for (Map.Entry<String, double[]> e : byClass.entrySet()) {
                String cls = e.getKey();
                String pkg = cls.startsWith("com.maixr") ? "com.maixr"
                        : cls.startsWith("net.minecraft") ? "net.minecraft"
                        : cls.startsWith("net.neoforged") ? "net.neoforged"
                        : cls.contains(".") ? cls.substring(0, cls.indexOf('.'))
                        : "(默认包)";
                byPkg.merge(pkg, e.getValue()[0], Double::sum);
            }
            System.out.println("-- 按包自耗时 --");
            byPkg.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(e -> System.out.printf("  %6.2f%%  %s%n",
                            threadTotal > 0 ? 100.0 * e.getValue() / threadTotal : 0.0,
                            e.getKey()));
        }
    }

    /** 打印重调用链：total 超过阈值的节点逐层下钻（火焰图文本版）。 */
    static void printHeavyChain(Map<Integer, StackTraceNode> byIdx, int idx,
                                double threshold, int depth, StringBuilder indent) {
        StackTraceNode n = byIdx.get(idx);
        if (n == null || depth > 40) {
            return;
        }
        double total = n.getTimesList().stream().mapToDouble(Double::doubleValue).sum();
        if (total < threshold) {
            return;
        }
        System.out.printf("%s%-70.70s total=%10.1f%n", indent,
                n.getClassName().substring(n.getClassName().lastIndexOf('.') + 1)
                        + "." + n.getMethodName() + ":" + n.getLineNumber(), total);
        for (int c : n.getChildrenRefsList()) {
            StackTraceNode cn = byIdx.get(c);
            if (cn != null) {
                double ct = cn.getTimesList().stream().mapToDouble(Double::doubleValue).sum();
                if (ct >= threshold) {
                    printHeavyChain(byIdx, c, threshold, depth + 1,
                            new StringBuilder(indent).append("  "));
                }
            }
        }
    }

    static double dfs(Map<Integer, StackTraceNode> byIdx, int idx, Map<String, double[]> byClass) {
        StackTraceNode n = byIdx.get(idx);
        if (n == null) {
            return 0;
        }
        double total = n.getTimesList().stream().mapToDouble(Double::doubleValue).sum();
        double childSum = 0;
        for (int c : n.getChildrenRefsList()) {
            childSum += dfs(byIdx, c, byClass);
        }
        double self = Math.max(0, total - childSum);
        byClass.computeIfAbsent(n.getClassName(), k -> new double[2])[0] += self;
        byClass.get(n.getClassName())[1] += total;
        return total;
    }
}
