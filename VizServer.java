import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import com.sun.net.httpserver.*;

import javax.tools.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Algorithm Visualizer: local helper for Java code (recursion, DP, trees, linked lists, graphs, grids).
 *
 *   java VizServer.java            (from the folder that contains recursion_visualizer.html)
 *
 * It serves the page on http://localhost:8765 and, for every "Animate" click in Java mode,
 * compiles the pasted code, runs it under the JDK debugger API (JDI) and returns a trace:
 * method calls / returns, plus every change to arrays and java.util collections.
 */
public class VizServer {
    static final int MAX_EVENTS = 6000, MAX_NODES = 700, MAX_STEPS = 30000, MAX_STRUCTS = 60, TIME_LIMIT_S = 40;
    static String html, token;
    static int port = 8765;
    static Path saveDir;                       // my_problems/ next to the page: one JSON file per saved problem

    public static void main(String[] args) throws Exception {
        boolean openBrowser = true;
        Path dir = Path.of(System.getProperty("user.dir"));
        for (String a : args) {
            if (a.equals("--no-browser")) openBrowser = false;
            else if (a.startsWith("--port=")) port = Integer.parseInt(a.substring(7));
            else dir = Path.of(a);
        }
        Path page = null;
        for (Path c : List.of(dir.resolve("recursion_visualizer.html"), dir.resolve("recursion-visualizer/recursion_visualizer.html")))
            if (Files.exists(c)) { page = c; break; }
        if (page == null) { System.err.println("Cannot find recursion_visualizer.html in " + dir + " (pass the folder as an argument)"); System.exit(1); }
        html = Files.readString(page);
        saveDir = page.toAbsolutePath().getParent().resolve("my_problems");
        byte[] rnd = new byte[16]; new SecureRandom().nextBytes(rnd);
        StringBuilder tk = new StringBuilder(); for (byte b : rnd) tk.append(String.format("%02x", b));
        token = tk.toString();

        HttpServer server = null;
        for (int p = port; p < port + 15 && server == null; p++) {
            try { server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 0); port = p; }
            catch (BindException ignored) { }
        }
        if (server == null) { System.err.println("No free port found"); System.exit(1); }

        server.createContext("/", ex -> {
            try {
                if (!hostOk(ex)) { send(ex, 403, "text/plain", "bad host"); return; }
                String path = ex.getRequestURI().getPath();
                if (path.equals("/") || path.equals("/index.html") || path.equals("/recursion_visualizer.html")) {
                    String page2 = html.replace("</head>", "<script>window.VIZ_TOKEN=\"" + token + "\";</script></head>");
                    send(ex, 200, "text/html; charset=utf-8", page2);
                } else send(ex, 404, "text/plain", "not found");
            } catch (Exception e) { e.printStackTrace(); }
        });
        server.createContext("/trace", ex -> {
            try {
                if (!hostOk(ex) || !token.equals(ex.getRequestHeaders().getFirst("X-Viz-Token")) || !ex.getRequestMethod().equals("POST")) {
                    send(ex, 403, "application/json", "{\"ok\":false,\"error\":\"forbidden\"}"); return;
                }
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int k = body.indexOf("\n@@@CODE@@@\n");
                String entry = k < 0 ? "" : body.substring(0, k), code = k < 0 ? body : body.substring(k + 12);
                String out;
                synchronized (VizServer.class) {
                    try { out = new Run(entry.trim(), code).execute(); }
                    catch (Throwable t) { t.printStackTrace(); out = "{\"ok\":false,\"error\":" + json("Internal error: " + t) + "}"; }
                }
                send(ex, 200, "application/json; charset=utf-8", out);
            } catch (Exception e) { e.printStackTrace(); }
        });
        server.createContext("/problems", ex -> {
            try {
                if (!authOk(ex)) { send(ex, 403, "application/json", "{\"ok\":false,\"error\":\"forbidden\"}"); return; }
                String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod(), out;
                try {
                    if (path.equals("/problems") && method.equals("GET")) out = json(listProblems());
                    else if (method.equals("POST")) {
                        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
                        if (raw.length > MAX_BODY) throw new IllegalArgumentException("Problem is too large to save");
                        String body = new String(raw, StandardCharsets.UTF_8);
                        synchronized (SAVE_LOCK) {
                            if (path.equals("/problems/save")) {
                                int k = body.indexOf("\n@@@CODE@@@\n");
                                if (k < 0) throw new IllegalArgumentException("bad request");
                                out = "{\"ok\":true,\"key\":" + json(saveProblem(body.substring(0, k).trim(), body.substring(k + 12))) + "}";
                            } else if (path.equals("/problems/delete")) {
                                Files.deleteIfExists(fileFor(body.trim())); out = "{\"ok\":true}";
                            } else if (path.equals("/problems/rename")) {
                                int k = body.indexOf("\n@@@NAME@@@\n");
                                if (k < 0) throw new IllegalArgumentException("bad request");
                                out = "{\"ok\":true,\"key\":" + json(renameProblem(body.substring(0, k).trim(), body.substring(k + 12).trim())) + "}";
                            } else { send(ex, 404, "application/json", "{\"ok\":false,\"error\":\"not found\"}"); return; }
                        }
                    } else { send(ex, 404, "application/json", "{\"ok\":false,\"error\":\"not found\"}"); return; }
                } catch (IllegalArgumentException | IOException e) { out = "{\"ok\":false,\"error\":" + json(e.getMessage()) + "}"; }
                send(ex, 200, "application/json; charset=utf-8", out);
            } catch (Exception e) { e.printStackTrace(); }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        String url = "http://localhost:" + port + "/";
        System.out.println("Algorithm Visualizer running at " + url + "   (Ctrl+C / Stop to quit)");
        if (openBrowser) openBrowser(url);
    }

    static boolean hostOk(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        return h != null && (h.equals("localhost:" + port) || h.equals("127.0.0.1:" + port));
    }
    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
    static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("mac")) {
                try { new ProcessBuilder("open", "-a", "Google Chrome", url).start().waitFor(); return; } catch (Exception ignore) { }
                new ProcessBuilder("open", url).start();
            } else if (os.contains("win")) new ProcessBuilder("cmd", "/c", "start", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception e) { System.out.println("Open this URL in Chrome: " + url); }
    }

    /* ---------------- saved problems: my_problems/<key>.json ---------------- */
    static final Object SAVE_LOCK = new Object();
    static final int MAX_PROBLEMS = 100, MAX_INPUTS = 10, MAX_BODY = 300_000;

    static boolean authOk(HttpExchange ex) { return hostOk(ex) && token.equals(ex.getRequestHeaders().getFirst("X-Viz-Token")); }

    static String cleanKey(String s) {
        s = s.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
    /** The key is the method called in the Run box: new Solution().levelOrder(..) -> levelOrder. For statement-style input
     *  (several statements) it is the first class instantiated, e.g. MinStack. */
    static String keyOf(String entry) {
        String e = entry.trim(); Matcher m;
        boolean multi = e.endsWith(";") ? e.substring(0, e.length() - 1).contains(";") : false;
        if (multi && (m = Pattern.compile("\\bnew\\s+([A-Za-z_]\\w*)").matcher(e)).find()) return cleanKey(m.group(1));
        if ((m = Pattern.compile("\\.\\s*([A-Za-z_]\\w*)\\s*\\(").matcher(e)).find()) return cleanKey(m.group(1));
        if ((m = Pattern.compile("^\\s*([A-Za-z_]\\w*)\\s*\\(").matcher(e)).find()) return cleanKey(m.group(1));
        if ((m = Pattern.compile("\\bnew\\s+([A-Za-z_]\\w*)").matcher(e)).find()) return cleanKey(m.group(1));
        return "problem";
    }
    static Path fileFor(String key) {
        if (!key.matches("[A-Za-z0-9_-]{1,60}")) throw new IllegalArgumentException("bad name");
        Path f = saveDir.resolve(key + ".json").normalize();
        if (!f.startsWith(saveDir)) throw new IllegalArgumentException("bad name");
        return f;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> readProblem(Path f) {
        try {
            if (!Files.exists(f)) return null;
            Object o = new JP(Files.readString(f)).value();
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (Exception e) { return null; }
    }
    static void writeProblem(Path f, Map<String, Object> m) throws IOException {
        Files.createDirectories(saveDir);
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (var e : m.entrySet()) { if (!first) sb.append(",\n"); first = false; sb.append("  ").append(json(e.getKey())).append(": ").append(json(e.getValue())); }
        sb.append("\n}\n");
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }
    static List<Map<String, Object>> listProblems() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(saveDir)) return out;
        try (var files = Files.list(saveDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.getFileName().toString().endsWith(".json")) continue;
                Map<String, Object> m = readProblem(f);
                if (m != null && m.get("key") instanceof String && m.get("code") instanceof String && m.get("inputs") instanceof List) out.add(m);
            }
        }
        out.sort((a, b) -> Long.compare(num(b.get("savedAt")), num(a.get("savedAt"))));
        return out;
    }
    static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }

    @SuppressWarnings("unchecked")
    static String saveProblem(String entry, String code) throws IOException {
        if (entry.isBlank() || code.isBlank()) throw new IllegalArgumentException("nothing to save");
        String key = keyOf(entry);
        Path f = fileFor(key);
        Map<String, Object> old = readProblem(f), m = new LinkedHashMap<>();
        List<Object> inputs = new ArrayList<>();
        if (old != null && old.get("inputs") instanceof List<?> l) inputs.addAll((List<Object>) l);
        inputs.remove(entry); inputs.add(0, entry);
        while (inputs.size() > MAX_INPUTS) inputs.remove(inputs.size() - 1);
        m.put("key", key);
        m.put("name", old != null && old.get("name") instanceof String nm ? nm : key);
        m.put("savedAt", System.currentTimeMillis());
        m.put("inputs", inputs);
        m.put("code", code);
        writeProblem(f, m);
        var all = listProblems();                                          // keep the folder bounded: drop the oldest
        for (int i = MAX_PROBLEMS; i < all.size(); i++) Files.deleteIfExists(fileFor((String) all.get(i).get("key")));
        return key;
    }
    static String renameProblem(String key, String newName) throws IOException {
        Map<String, Object> m = readProblem(fileFor(key));
        if (m == null) throw new IllegalArgumentException("That problem no longer exists");
        String nk = cleanKey(newName);
        if (newName.isBlank() || nk.replace("_", "").isEmpty()) throw new IllegalArgumentException("Please enter a name with letters or digits");
        if (!nk.equals(key) && Files.exists(fileFor(nk))) throw new IllegalArgumentException("A problem called \"" + nk + "\" already exists");
        m.put("key", nk); m.put("name", newName.length() > 60 ? newName.substring(0, 60) : newName);
        writeProblem(fileFor(nk), m);
        if (!nk.equals(key)) Files.deleteIfExists(fileFor(key));
        return nk;
    }

    /** minimal JSON reader (objects, arrays, strings, numbers, true/false/null) for the saved files */
    static class JP {
        final String s; int i = 0;
        JP(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws(); char c = s.charAt(i);
            if (c == '{') {
                i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                for (;;) { ws(); String k = str(); ws(); i++; m.put(k, value()); ws(); if (s.charAt(i++) == '}') return m; }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                for (;;) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; }
            }
            if (c == '"') return str();
            int st = i; while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            String t = s.substring(st, i);
            if (t.equals("true")) return Boolean.TRUE; if (t.equals("false")) return Boolean.FALSE; if (t.equals("null")) return null;
            try { return Long.parseLong(t); } catch (NumberFormatException e) { return Double.parseDouble(t); }
        }
        String str() {
            StringBuilder sb = new StringBuilder(); i++;
            for (;;) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char d = s.charAt(i++);
                switch (d) {
                    case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r'); case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(d);
                }
            }
        }
    }

    /* ---------------- JSON ---------------- */
    static String json(Object o) { StringBuilder sb = new StringBuilder(); json(o, sb); return sb.toString(); }
    @SuppressWarnings("unchecked")
    static void json(Object o, StringBuilder sb) {
        if (o == null) sb.append("null");
        else if (o instanceof Boolean) sb.append(o);
        else if (o instanceof Long || o instanceof Integer) sb.append(o);
        else if (o instanceof Double d) { if (d.isNaN() || d.isInfinite()) json(d.isNaN() ? "NaN" : d > 0 ? "∞" : "-∞", sb); else sb.append(d); }
        else if (o instanceof String s) {
            sb.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\""); case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n"); case '\r' -> sb.append("\\r"); case '\t' -> sb.append("\\t");
                    default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                }
            }
            sb.append('"');
        }
        else if (o instanceof A a) { sb.append("{\"t\":\"a\",\"v\":"); json(a.v, sb); sb.append('}'); }
        else if (o instanceof G g) { sb.append("{\"t\":\"g\",\"kind\":"); json(g.kind, sb); sb.append(",\"rows\":"); json(g.rows, sb); sb.append(",\"ptrs\":"); json(g.ptrs, sb); sb.append('}'); }
        else if (o instanceof M m) {
            sb.append("{\"t\":\"m\",\"v\":[");
            boolean first = true;
            for (var e : m.v.entrySet()) { if (!first) sb.append(','); first = false; sb.append('['); json(e.getKey(), sb); sb.append(','); json(e.getValue(), sb); sb.append(']'); }
            sb.append("]}");
        }
        else if (o instanceof S s) { sb.append("{\"t\":\"s\",\"v\":"); json(new ArrayList<>(s.v), sb); sb.append('}'); }
        else if (o instanceof List<?> l) { sb.append('['); boolean f = true; for (Object x : l) { if (!f) sb.append(','); f = false; json(x, sb); } sb.append(']'); }
        else if (o instanceof Map<?, ?> m) {
            sb.append('{'); boolean f = true;
            for (var e : m.entrySet()) { if (!f) sb.append(','); f = false; json(String.valueOf(e.getKey()), sb); sb.append(':'); json(e.getValue(), sb); }
            sb.append('}');
        }
        else json(String.valueOf(o), sb);
    }

    /* ---------------- snapshot model ---------------- */
    static class A { final List<Object> v; A(List<Object> v) { this.v = v; } }
    static class M { final LinkedHashMap<Object, Object> v = new LinkedHashMap<>(); }
    static class S { final LinkedHashSet<Object> v = new LinkedHashSet<>(); }
    /** preformatted text (e.g. a linked list shown as 1→2→3) */
    static class Raw { final String s; Raw(String s) { this.s = s; }
        @Override public String toString() { return s; }
        @Override public boolean equals(Object o) { return o instanceof Raw r && r.s.equals(s); }
        @Override public int hashCode() { return s.hashCode(); } }
    /** snapshot of a node-link structure (binary tree or linked list): rows are [id, val, left, right, next] */
    static class G {
        final String kind; final List<List<Object>> rows; final Map<String, Long> ptrs; final String rowsKey, key;
        G(String kind, List<List<Object>> rows, Map<String, Long> ptrs) {
            this.kind = kind; this.rows = rows; this.ptrs = ptrs; rowsKey = json(rows); key = rowsKey + "|" + json(ptrs);
        }
    }

    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof A x && b instanceof A y) {
            if (x.v.size() != y.v.size()) return false;
            for (int i = 0; i < x.v.size(); i++) if (!same(x.v.get(i), y.v.get(i))) return false;
            return true;
        }
        if (a instanceof M x && b instanceof M y) {
            if (!x.v.keySet().equals(y.v.keySet())) return false;
            for (var e : x.v.entrySet()) if (!same(e.getValue(), y.v.get(e.getKey()))) return false;
            return true;
        }
        if (a instanceof S x && b instanceof S y) return x.v.equals(y.v);
        return a.equals(b);
    }
    static boolean container(Object o) { return o instanceof A || o instanceof M || o instanceof S; }

    static String text(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + s + "\"";
        if (o instanceof Raw r) return r.s;
        if (o instanceof Double d) return d.isInfinite() ? (d > 0 ? "∞" : "-∞") : String.valueOf(d);
        if (o instanceof A a) { StringBuilder sb = new StringBuilder("["); for (int i = 0; i < a.v.size(); i++) { if (i > 0) sb.append(','); sb.append(text(a.v.get(i))); } return sb.append(']').toString(); }
        if (o instanceof S s) { StringBuilder sb = new StringBuilder("{"); boolean f = true; for (Object x : s.v) { if (!f) sb.append(','); f = false; sb.append(text(x)); } return sb.append('}').toString(); }
        if (o instanceof M m) { StringBuilder sb = new StringBuilder("{"); boolean f = true; for (var e : m.v.entrySet()) { if (!f) sb.append(','); f = false; sb.append(text(e.getKey())).append('=').append(text(e.getValue())); } return sb.append('}').toString(); }
        return String.valueOf(o);
    }
    static String shorten(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }

    /* ---------------- one traced run ---------------- */
    static class Node {
        int id, parent = -1, newId = -1; String method, name, label = "", valStr = "", kind = "normal"; boolean keep;
        String retText = ""; List<Integer> keepKids = new ArrayList<>();
        List<Integer> kids = new ArrayList<>(); List<String> found = new ArrayList<>();
        List<Object[]> parts = new ArrayList<>();      // {text, arrayId or null}
    }

    static class Run {
        final String entry, code; final String[] lines;
        Path dir, out;
        VirtualMachine vm; EventRequestManager erm;
        final List<EventRequest> hooks = new ArrayList<>();
        StepRequest step; ThreadReference mainThread;
        final List<Node> nodes = new ArrayList<>(); final ArrayDeque<Node> nstack = new ArrayDeque<>();
        final List<Map<String, Object>> events = new ArrayList<>();
        final List<Map<String, Object>> structs = new ArrayList<>();
        final Map<Long, Integer> sidById = new HashMap<>(); final Map<Integer, Object> last = new HashMap<>();
        final Map<String, Long> curVars = new HashMap<>(); final Map<String, Integer> curWatch = new HashMap<>();
        Set<Long> ownedNow = new HashSet<>();
        final Map<String, Integer> nameCount = new HashMap<>();
        List<Map<String, Object>> pending = new ArrayList<>(); final Set<String> pendingKeys = new HashSet<>();
        int steps = 0; boolean stopped = false; String stopReason = "";
        static final int MAX_LINE_EVENTS = 15000;
        int evLn = 0, lastLn = 0, lineCount = 0; boolean linesTruncated = false;
        final TreeMap<Integer, Integer> loopEnd = new TreeMap<>();         // loop header line -> last line of its body
        final Map<Long, Integer> hits = new HashMap<>();                   // (frame, header line) -> times control reached the header
        List<List<String>> varList = new ArrayList<>();
        final List<String> prelude = new ArrayList<>();
        final List<String> logs = new ArrayList<>(); String resultText = null; final StringBuilder stderr = new StringBuilder();

        Run(String entry, String code) { this.entry = entry; this.code = code; this.lines = code.split("\n", -1); try { findLoops(); } catch (Exception ignore) { } }

        String execute() throws Exception {
            dir = Files.createTempDirectory("vizjava"); out = dir.resolve("out"); Files.createDirectories(out);
            try {
                String err = compile();
                if (err != null) return "{\"ok\":false,\"error\":" + json(err) + "}";
                trace();
                return build();
            } finally { try { deleteTree(dir); } catch (Exception ignore) { } }
        }

        String compile() throws Exception {
            String user = code.replaceAll("(?m)^\\s*package\\s+[\\w.]+\\s*;", "")
                              .replaceAll("\\bpublic\\s+(class|interface|enum|record)\\b", "$1");
            // LeetCode supplies ListNode / TreeNode; add them (after the user's code so line numbers stay right) unless the user declared their own.
            String bare = code.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
            boolean ownList = Pattern.compile("\\b(class|record|interface)\\s+ListNode\\b").matcher(bare).find();
            boolean ownTree = Pattern.compile("\\b(class|record|interface)\\s+TreeNode\\b").matcher(bare).find();
            StringBuilder extra = new StringBuilder("\n");
            if (!ownList) { prelude.add("ListNode"); extra.append("class ListNode { int val; ListNode next; ListNode() {} ListNode(int val) { this.val = val; } ListNode(int val, ListNode next) { this.val = val; this.next = next; } }\n"); }
            if (!ownTree) { prelude.add("TreeNode"); extra.append("class TreeNode { int val; TreeNode left, right; TreeNode() {} TreeNode(int val) { this.val = val; } TreeNode(int val, TreeNode left, TreeNode right) { this.val = val; this.left = left; this.right = right; } }\n"); }
            Files.writeString(dir.resolve("UserCode.java"), "import java.util.*; import java.util.function.*; import java.util.stream.*; " + user + extra);
            boolean stmt = entry.endsWith(";");
            // Helpers usable in the Run box:  tree(3,9,20,null,null,15,7)   list(1,2,3)
            StringBuilder helpers = new StringBuilder(), printers = new StringBuilder();
            if (!ownTree) {
                helpers.append("  static TreeNode tree(Integer... a){ if(a.length==0||a[0]==null)return null; TreeNode root=new TreeNode(a[0]); Deque<TreeNode> q=new ArrayDeque<>(); q.add(root); int i=1;\n"
                    + "    while(!q.isEmpty()&&i<a.length){ TreeNode n=q.poll(); if(i<a.length&&a[i]!=null){n.left=new TreeNode(a[i]);q.add(n.left);} i++; if(i<a.length&&a[i]!=null){n.right=new TreeNode(a[i]);q.add(n.right);} i++; } return root; }\n"
                    + "  static String ts(TreeNode r){ if(r==null)return \"[]\"; List<String> o=new ArrayList<>(); LinkedList<TreeNode> q=new LinkedList<>(); q.add(r);\n"
                    + "    while(!q.isEmpty()&&o.size()<2000){ TreeNode n=q.poll(); if(n==null){o.add(\"null\");continue;} o.add(\"\"+n.val); q.add(n.left); q.add(n.right); }\n"
                    + "    int e=o.size(); while(e>0&&o.get(e-1).equals(\"null\"))e--; return o.subList(0,e).toString().replace(\", \",\",\"); }\n");
                printers.append("    if(o instanceof TreeNode t)return ts(t);\n");
            }
            if (!ownList) {
                helpers.append("  static ListNode list(int... a){ ListNode d=new ListNode(0),c=d; for(int x:a){c.next=new ListNode(x);c=c.next;} return d.next; }\n"
                    + "  static String ls(ListNode l){ StringBuilder b=new StringBuilder(\"[\"); int c=0; while(l!=null&&c++<1000){ if(c>1)b.append(','); b.append(l.val); l=l.next; } return b.append(']').toString(); }\n");
                printers.append("    if(o instanceof ListNode l)return ls(l);\n");
            }
            String main = "import java.util.*;\npublic class VizMain {\n" + helpers
                + "  static String s(Object o){ if(o==null)return \"null\"; if(o instanceof int[] a)return Arrays.toString(a); if(o instanceof long[] a)return Arrays.toString(a);\n"
                + "    if(o instanceof double[] a)return Arrays.toString(a); if(o instanceof boolean[] a)return Arrays.toString(a); if(o instanceof char[] a)return Arrays.toString(a);\n"
                + printers
                + "    if(o instanceof Object[] a)return Arrays.deepToString(a); return String.valueOf(o); }\n"
                + "  public static void main(String[] args) throws Throwable {\n"
                + (stmt ? "    " + entry + "\n    Object __r = \"\";\n" : "    Object __r = (" + entry + ");\n")
                + "    System.out.println(\"\\u0001RESULT\\u0001\" + s(__r));\n  }\n}\n";
            Files.writeString(dir.resolve("VizMain.java"), main);
            JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
            if (jc == null) return "This Java installation has no compiler. Run the helper with a JDK, not a JRE.";
            DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = jc.getStandardFileManager(dc, null, StandardCharsets.UTF_8)) {
                var units = fm.getJavaFileObjects(dir.resolve("UserCode.java"), dir.resolve("VizMain.java"));
                boolean ok = jc.getTask(null, fm, dc, List.of("-g", "-proc:none", "-Xlint:none", "-d", out.toString()), null, units).call();
                if (!ok) {
                    StringBuilder sb = new StringBuilder("Compile error:\n");
                    for (var d : dc.getDiagnostics()) if (d.getKind() == Diagnostic.Kind.ERROR) {
                        boolean inMain = d.getSource() != null && d.getSource().getName().endsWith("VizMain.java");
                        sb.append(inMain ? "  In the Run box: " : "  Line " + d.getLineNumber() + ": ").append(d.getMessage(Locale.ENGLISH).split("\n")[0]).append('\n');
                    }
                    if (stmt == false && sb.toString().contains("void")) sb.append("  (If the method returns void, end the Run box text with a semicolon.)\n");
                    return sb.toString();
                }
            }
            return null;
        }

        static void deleteTree(Path p) throws IOException {
            if (!Files.exists(p)) return;
            try (var w = Files.walk(p)) { w.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
        }

        /* ---- launch + event loop ---- */
        void trace() throws Exception {
            LaunchingConnector lc = Bootstrap.virtualMachineManager().defaultConnector();
            Map<String, Connector.Argument> a = lc.defaultArguments();
            a.get("main").setValue("VizMain");
            a.get("options").setValue("-cp " + out + " -Xss1m");
            vm = lc.launch(a);
            Process proc = vm.process();
            Thread to = drain(proc.getInputStream(), true), te = drain(proc.getErrorStream(), false);
            erm = vm.eventRequestManager();
            try (var files = Files.list(out)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String fn = f.getFileName().toString();
                    if (!fn.endsWith(".class") || fn.equals("VizMain.class") || prelude.contains(fn.substring(0, fn.length() - 6))) continue;
                    String cn = fn.substring(0, fn.length() - 6);
                    MethodEntryRequest en = erm.createMethodEntryRequest(); en.addClassFilter(cn); en.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); en.enable(); hooks.add(en);
                    MethodExitRequest ex = erm.createMethodExitRequest(); ex.addClassFilter(cn); ex.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); ex.enable(); hooks.add(ex);
                }
            }
            ScheduledExecutorService wd = Executors.newSingleThreadScheduledExecutor();
            wd.schedule(() -> { try { stopped = true; stopReason = "time limit"; vm.exit(1); } catch (Exception ignore) { } }, TIME_LIMIT_S, TimeUnit.SECONDS);
            EventQueue q = vm.eventQueue();
            boolean running = true;
            try {
                while (running) {
                    EventSet es = q.remove();
                    for (Event e : es) {
                        try {
                            if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) { running = false; }
                            else if (e instanceof MethodEntryEvent me) { if (isMain(me.thread())) onEntry(me); }
                            else if (e instanceof MethodExitEvent mx) { if (isMain(mx.thread())) onExit(mx); }
                            else if (e instanceof StepEvent se) { if (isMain(se.thread())) onStep(se); }
                        } catch (VMDisconnectedException vd) { throw vd; }
                        catch (Exception ex) { System.err.println("trace warning: " + ex); }
                    }
                    if (nodes.size() >= MAX_NODES || events.size() - lineCount >= MAX_EVENTS || steps >= MAX_STEPS) {
                        stopped = true; stopReason = "size limit";
                        try { vm.exit(1); } catch (Exception ignore) { }
                        break;
                    }
                    if (running) es.resume();
                }
            } catch (VMDisconnectedException ignore) { }
            wd.shutdownNow();
            try { vm.exit(0); } catch (Exception ignore) { }
            to.join(2000); te.join(2000);
        }

        boolean isMain(ThreadReference t) { return "main".equals(t.name()); }
        Thread drain(InputStream is, boolean isOut) {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!isOut) { if (stderr.length() < 3000) stderr.append(line).append('\n'); continue; }
                        if (line.startsWith("\u0001RESULT\u0001")) resultText = line.substring(8);
                        else if (logs.size() < 200) logs.add(line);
                    }
                } catch (IOException ignore) { }
            });
            t.setDaemon(true); t.start(); return t;
        }

        static boolean skip(Method m) { String n = m.name(); return n.startsWith("<") || n.startsWith("lambda$") || m.isSynthetic() || m.isBridge(); }

        void createStep(ThreadReference t) {
            if (step != null) { try { erm.deleteEventRequest(step); } catch (Exception ignore) { } }
            step = erm.createStepRequest(t, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
            for (String ex : new String[]{"java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "VizMain"}) step.addClassExclusionFilter(ex);
            for (String ex : prelude) step.addClassExclusionFilter(ex);
            step.addCountFilter(1);
            step.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            step.enable();
        }

        void onEntry(MethodEntryEvent e) throws Exception {
            Method m = e.method(); if (skip(m)) return;
            ThreadReference t = e.thread();
            evLn = lastLn;
            diff(t);
            Node n = new Node(); n.id = nodes.size(); nodes.add(n);
            n.method = m.declaringType().name() + "." + m.name() + m.signature(); n.name = m.name();
            Node par = nstack.peek(); if (par != null) { n.parent = par.id; par.kids.add(n.id); }
            try {
                List<LocalVariable> ps = m.arguments(); List<Value> vs = t.frame(0).getArgumentValues();
                for (int i = 0; i < ps.size() && i < vs.size(); i++) {
                    Value v = vs.get(i); String pn = ps.get(i).name();
                    if (v == null) { n.parts.add(new Object[]{"null", null}); continue; }
                    if (v instanceof ArrayReference ar) {
                        boolean small = ar.length() <= 6 && !pn.matches("(?i)memo|cache|dp|table|mem|visited|seen|res|result|ans|out|used");
                        if (small) { Object s = snapOf(v, t, 0); n.parts.add(new Object[]{text(s), ar.uniqueID()}); }
                        else n.parts.add(new Object[]{null, ar.uniqueID()});
                    } else if (v instanceof ObjectReference nr && nodeInfo(nr) != null) {
                        Object nv = valOf(nr, nodeInfo(nr));
                        n.parts.add(new Object[]{shorten(nv == null ? "null" : text(nv), 12), null});
                    } else if (v instanceof ObjectReference br && br.referenceType().name().matches("java\\.lang\\.String(Builder|Buffer)")) {
                        String bt = readBuilder(br);
                        n.parts.add(new Object[]{shorten(bt == null ? "{StringBuilder}" : "\"" + bt + "\"", 12), null});
                    } else if (v instanceof PrimitiveValue || v instanceof StringReference) {
                        Object s = snapOf(v, t, 0);
                        String tx = v instanceof CharValue ? "'" + s + "'" : text(s);
                        n.parts.add(new Object[]{shorten(tx, 12), null});
                    }
                }
            } catch (Exception ex) { /* no -g info etc. */ }
            nstack.push(n);
            Map<String, Object> ev = ev("call"); ev.put("nid", n.id); ev.put("reads", flush()); events.add(ev);
            if (step == null) {                                  // the very first line of the outermost method is not reported by the step request
                int el = e.location().lineNumber();
                if (el >= 1 && el <= lines.length && lineCount < MAX_LINE_EVENTS) { events.add(lineEvent(el)); lineCount++; }
                lastLn = el;
                createStep(t);
            }
        }

        void onExit(MethodExitEvent e) throws Exception {
            Method m = e.method(); if (skip(m)) return;
            ThreadReference t = e.thread();
            evLn = lastLn;
            diff(t);
            Node n = nstack.poll(); if (n == null) return;
            Value rv = e.returnValue();
            Object s = rv instanceof VoidValue ? "void" : snapOf(rv, t, 0);
            n.valStr = rv instanceof VoidValue ? "void" : shorten(rv instanceof CharValue ? "'" + s + "'" : text(s), 18);
            n.retText = rv instanceof VoidValue ? "void" : (rv instanceof CharValue ? "'" + s + "'" : text(s));
            Map<String, Object> ev = ev("ret"); ev.put("nid", n.id); ev.put("val", n.valStr); ev.put("reads", flush()); events.add(ev);
        }

        void onStep(StepEvent e) throws Exception {
            steps++;
            ThreadReference t = e.thread();
            evLn = lastLn;                                  // effects found now were caused by the line that just ran
            diff(t);
            int ln = e.location().lineNumber();
            if (ln >= 1 && ln <= lines.length) {
                extractReads(lines[ln - 1]);
                if (lineCount < MAX_LINE_EVENTS) { events.add(lineEvent(ln)); lineCount++; }
                else linesTruncated = true;
            }
            lastLn = ln;
            createStep(t);
        }

        /** "about to run line ln": current frame, variables and loop iteration counters */
        Map<String, Object> lineEvent(int ln) {
            Node fn = nstack.peek(); long fid = fn == null ? -1 : fn.id;
            Map<String, Object> le = new LinkedHashMap<>();
            le.put("type", "line"); le.put("ln", ln); le.put("fid", fid); le.put("m", fn == null ? "" : fn.name);
            le.put("vars", varList);
            Integer self = loopEnd.containsKey(ln) ? ln : null;
            if (self != null) {                             // reaching a header: one more iteration; inner loops start over
                hits.merge(fid * 100003L + ln, 1, Integer::sum);
                for (int h : loopEnd.subMap(ln, false, loopEnd.get(ln), true).keySet()) hits.remove(fid * 100003L + h);
            }
            List<List<Object>> loops = new ArrayList<>();
            for (var lp : loopEnd.headMap(ln, true).entrySet())
                if (ln <= lp.getValue()) loops.add(new ArrayList<>(Arrays.asList((Object) lp.getKey(), (long) hits.getOrDefault(fid * 100003L + lp.getKey(), 0))));
            le.put("loops", loops);
            varList = new ArrayList<>();
            return le;
        }

        /** find for/while loops in the source: header line -> last body line (strings and comments ignored) */
        void findLoops() {
            char[] c = code.toCharArray(); int n = c.length;
            for (int i = 0; i < n; i++) {                   // blank out comments, strings and char literals (keep newlines)
                if (c[i] == '/' && i + 1 < n && c[i + 1] == '/') { while (i < n && c[i] != '\n') c[i++] = ' '; i--; }
                else if (c[i] == '/' && i + 1 < n && c[i + 1] == '*') { int j = i; i += 2; while (i + 1 < n && !(c[i] == '*' && c[i + 1] == '/')) i++; for (int k = j; k <= Math.min(i + 1, n - 1); k++) if (c[k] != '\n') c[k] = ' '; i++; }
                else if (c[i] == '"' || c[i] == '\'') { char q = c[i]; int j = i++; while (i < n && c[i] != q && c[i] != '\n') { if (c[i] == '\\') i++; i++; } for (int k = j; k <= Math.min(i, n - 1); k++) if (c[k] != '\n') c[k] = ' '; }
            }
            String m = new String(c);
            int[] lineOf = new int[n + 1]; int ln = 1; for (int i = 0; i < n; i++) { lineOf[i] = ln; if (c[i] == '\n') ln++; } lineOf[n] = ln;
            Matcher mt = Pattern.compile("(?m)^[ \\t]*(?:[\\w$]+\\s*:\\s*)?(for|while)\\s*\\(").matcher(m);
            while (mt.find()) {
                int open = mt.end() - 1, depth = 0, i = open;
                for (; i < n; i++) { if (c[i] == '(') depth++; else if (c[i] == ')' && --depth == 0) break; }
                if (i >= n) continue;
                int j = i + 1; while (j < n && Character.isWhitespace(c[j])) j++;
                if (j >= n || c[j] == ';') continue;        // "} while (x);" tail of a do-while
                int end;
                if (c[j] == '{') { int d = 0, k = j; for (; k < n; k++) { if (c[k] == '{') d++; else if (c[k] == '}' && --d == 0) break; } end = lineOf[Math.min(k, n)]; }
                else { int k = j, d = 0; for (; k < n; k++) { if (c[k] == '(' || c[k] == '{') d++; else if (c[k] == ')' || c[k] == '}') d--; else if (c[k] == ';' && d <= 0) break; } end = lineOf[Math.min(k, n)]; }
                loopEnd.put(lineOf[mt.start()] + (m.charAt(mt.start()) == '\n' ? 1 : 0), end);
            }
        }

        Map<String, Object> ev(String type) { Map<String, Object> m = new LinkedHashMap<>(); m.put("type", type); if (evLn > 0) m.put("ln", evLn); return m; }
        List<Map<String, Object>> flush() { var r = pending; pending = new ArrayList<>(); pendingKeys.clear(); return r; }

        /* ---- values -> snapshots ---- */
        boolean watchable(Value v) {
            if (v instanceof ArrayReference) return true;
            if (v instanceof ObjectReference o) {
                String n = o.referenceType().name();
                return n.startsWith("java.util.") && n.matches(".*(List|Map|Set|Deque|Queue|Stack|Vector).*");
            }
            return false;
        }
        String kindOf(Value v) {
            if (v instanceof ArrayReference) return "array";
            String n = ((ObjectReference) v).referenceType().name();
            return n.contains("Map") ? "Map" : n.contains("Set") ? "Set" : "List";
        }

        Object snapOf(Value v, ThreadReference t, int depth) throws Exception {
            if (v == null) return null;
            if (v instanceof BooleanValue b) return b.value();
            if (v instanceof CharValue c) return String.valueOf(c.value());
            if (v instanceof FloatValue || v instanceof DoubleValue) return ((PrimitiveValue) v).doubleValue();
            if (v instanceof PrimitiveValue p) return p.longValue();
            if (v instanceof StringReference s) return s.value();
            if (v instanceof ArrayReference ar) {
                if (depth > 3) return "…";
                int n = Math.min(ar.length(), 200);
                List<Object> out = new ArrayList<>();
                if (n > 0) for (Value x : ar.getValues(0, n)) {
                    if (x instanceof ArrayReference xa) ownedNow.add(xa.uniqueID());
                    out.add(snapOf(x, t, depth + 1));
                }
                return new A(out);
            }
            if (v instanceof ObjectReference o) {
                String cn = o.referenceType().name();
                if (cn.matches("java\\.lang\\.(Integer|Long|Short|Byte|Double|Float|Boolean|Character)")) {
                    Value inner = o.getValue(o.referenceType().fieldByName("value"));
                    Object r = snapOf(inner, t, depth);
                    return r;
                }
                NI nodeI = nodeInfo(o);
                if (nodeI != null) return new Raw(nodeText(o, nodeI));
                if (watchable(o)) {
                    if (depth > 3) return "…";
                    Object direct = readCollection(o, t, depth);
                    if (direct != null) return direct;
                    String s = invokeToString(o, t);          // unknown collection type: fall back to toString()
                    return parseCollection(s, kindOf(o));
                }
                if (cn.equals("java.lang.StringBuilder") || cn.equals("java.lang.StringBuffer")) {
                    String sbt = readBuilder(o);
                    if (sbt != null) return sbt;
                }
                if (cn.equals("java.lang.String")) return String.valueOf(o);
                return "{" + cn.substring(cn.lastIndexOf('.') + 1) + "}";
            }
            return String.valueOf(v);
        }

        /* ---- read java.util collections straight from their fields (no code is run inside the program) ---- */
        Value fld(Value o, String name) {
            if (!(o instanceof ObjectReference r)) return null;
            Field f = r.referenceType().fieldByName(name);
            return f == null ? null : r.getValue(f);
        }
        int intFld(Value o, String name) { return fld(o, name) instanceof IntegerValue iv ? iv.value() : 0; }

        Object readCollection(ObjectReference o, ThreadReference t, int depth) throws Exception {
            String cn = o.referenceType().name();
            final int CAP = 200;
            switch (cn) {
                case "java.util.ArrayList", "java.util.Vector", "java.util.Stack" -> {
                    Value el = fld(o, "elementData"); if (!(el instanceof ArrayReference ar)) return null;
                    int sz = Math.min(cn.equals("java.util.ArrayList") ? intFld(o, "size") : intFld(o, "elementCount"), CAP);
                    List<Object> out = new ArrayList<>();
                    if (sz > 0) for (Value x : ar.getValues(0, sz)) out.add(snapOf(x, t, depth + 1));
                    return new A(out);
                }
                case "java.util.PriorityQueue" -> {
                    Value el = fld(o, "queue"); if (!(el instanceof ArrayReference ar)) return null;
                    int sz = Math.min(intFld(o, "size"), CAP);
                    List<Object> out = new ArrayList<>();
                    if (sz > 0) for (Value x : ar.getValues(0, sz)) out.add(snapOf(x, t, depth + 1));
                    return new A(out);
                }
                case "java.util.ArrayDeque" -> {
                    Value el = fld(o, "elements"); if (!(el instanceof ArrayReference ar)) return null;
                    int head = intFld(o, "head"), tail = intFld(o, "tail"), len = ar.length();
                    List<Value> vs = ar.getValues(0, len); List<Object> out = new ArrayList<>();
                    for (int i = head, c = 0; i != tail && c < CAP; i = (i + 1) & (len - 1), c++) out.add(snapOf(vs.get(i), t, depth + 1));
                    return new A(out);
                }
                case "java.util.LinkedList" -> {
                    List<Object> out = new ArrayList<>();
                    Value nd = fld(o, "first");
                    for (int c = 0; nd != null && c < CAP; c++) { out.add(snapOf(fld(nd, "item"), t, depth + 1)); nd = fld(nd, "next"); }
                    return new A(out);
                }
                case "java.util.HashMap", "java.util.LinkedHashMap" -> {
                    M m = new M(); readHashMap(o, t, depth, (k, v) -> m.v.put(k, v)); return m;
                }
                case "java.util.HashSet", "java.util.LinkedHashSet" -> {
                    Value inner = fld(o, "map"); if (!(inner instanceof ObjectReference io)) return null;
                    S s = new S(); readHashMap(io, t, depth, (k, v) -> s.v.add(k)); return s;
                }
                case "java.util.TreeMap" -> { M m = new M(); readTree(fld(o, "root"), t, depth, new int[]{0}, (k, v) -> m.v.put(k, v)); return m; }
                case "java.util.TreeSet" -> {
                    Value inner = fld(o, "m"); if (!(inner instanceof ObjectReference io) || !io.referenceType().name().equals("java.util.TreeMap")) return null;
                    S s = new S(); readTree(fld(io, "root"), t, depth, new int[]{0}, (k, v) -> s.v.add(k)); return s;
                }
                default -> { return null; }
            }
        }
        interface KV { void put(Object k, Object v); }
        void readHashMap(ObjectReference o, ThreadReference t, int depth, KV out) throws Exception {
            int count = 0;
            if (o.referenceType().name().equals("java.util.LinkedHashMap")) {
                Value e = fld(o, "head");
                for (; e != null && count < 200; count++) { out.put(snapOf(fld(e, "key"), t, depth + 1), snapOf(fld(e, "value"), t, depth + 1)); e = fld(e, "after"); }
                return;
            }
            Value tb = fld(o, "table"); if (!(tb instanceof ArrayReference ar)) return;
            for (Value b : ar.getValues(0, ar.length())) {
                for (Value e = b; e != null && count < 200; count++) { out.put(snapOf(fld(e, "key"), t, depth + 1), snapOf(fld(e, "value"), t, depth + 1)); e = fld(e, "next"); }
            }
        }
        void readTree(Value e, ThreadReference t, int depth, int[] count, KV out) throws Exception {
            if (e == null || count[0] >= 200) return;
            readTree(fld(e, "left"), t, depth, count, out);
            if (count[0]++ < 200) out.put(snapOf(fld(e, "key"), t, depth + 1), snapOf(fld(e, "value"), t, depth + 1));
            readTree(fld(e, "right"), t, depth, count, out);
        }

        /** text of a StringBuilder/StringBuffer, read straight from its fields (no code runs in the target) */
        String readBuilder(ObjectReference o) {
            try {
                ReferenceType rt = o.referenceType();
                Field fc = rt.fieldByName("count"), fv = rt.fieldByName("value"), fk = rt.fieldByName("coder");
                if (fc == null || fv == null) return null;
                int n = ((IntegerValue) o.getValue(fc)).value();
                ArrayReference arr = (ArrayReference) o.getValue(fv);
                if (arr == null) return null;
                int cap = Math.min(n, 4000);
                StringBuilder out = new StringBuilder();
                boolean utf16 = fk != null && ((ByteValue) o.getValue(fk)).value() == 1;
                if (cap > 0) {
                    List<Value> vs = arr.getValues(0, utf16 ? cap * 2 : cap);
                    for (int i = 0; i < cap; i++) {
                        if (vs.get(0) instanceof CharValue) out.append(((CharValue) vs.get(i)).value());
                        else if (utf16) out.append((char) ((((ByteValue) vs.get(2 * i + 1)).value() & 0xff) << 8 | (((ByteValue) vs.get(2 * i)).value() & 0xff)));
                        else out.append((char) (((ByteValue) vs.get(i)).value() & 0xff));
                    }
                }
                return out.toString();
            } catch (Exception e) { return null; }
        }

        String invokeToString(ObjectReference o, ThreadReference t) throws Exception {
            setHooks(false);
            try {
                Method m = o.referenceType().methodsByName("toString", "()Ljava/lang/String;").get(0);
                Value r = o.invokeMethod(t, m, Collections.emptyList(), ObjectReference.INVOKE_SINGLE_THREADED);
                String s = r == null ? "null" : ((StringReference) r).value();
                return s.length() > 4000 ? s.substring(0, 4000) : s;
            } finally { setHooks(true); }
        }
        void setHooks(boolean on) { for (EventRequest r : hooks) r.setEnabled(on); }

        Object parseCollection(String s, String kind) {
            s = s.trim();
            if (kind.equals("Map")) return parseVal(s.startsWith("{") ? s : "{" + s + "}", true);
            Object v = parseVal(s, false);
            if (kind.equals("Set") && v instanceof A a) { S set = new S(); set.v.addAll(a.v); return set; }
            return v;
        }
        List<String> split(String s) {         // top-level comma split
            List<String> out = new ArrayList<>(); int d = 0, st = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '[' || c == '{' || c == '(') d++; else if (c == ']' || c == '}' || c == ')') d--;
                else if (c == ',' && d == 0) { out.add(s.substring(st, i).trim()); st = i + 1; }
            }
            String tail = s.substring(st).trim(); if (!tail.isEmpty() || !out.isEmpty()) out.add(tail);
            return out;
        }
        Object parseVal(String s, boolean mapCtx) {
            s = s.trim();
            if (s.startsWith("[") && s.endsWith("]")) { List<Object> l = new ArrayList<>(); for (String p : split(s.substring(1, s.length() - 1))) l.add(parseVal(p, false)); return new A(l); }
            if (s.startsWith("{") && s.endsWith("}")) {
                M m = new M();
                for (String p : split(s.substring(1, s.length() - 1))) {
                    int d = 0, eq = -1;
                    for (int i = 0; i < p.length(); i++) { char c = p.charAt(i); if (c == '[' || c == '{') d++; else if (c == ']' || c == '}') d--; else if (c == '=' && d == 0) { eq = i; break; } }
                    if (eq < 0) continue;
                    m.v.put(parseVal(p.substring(0, eq), false), parseVal(p.substring(eq + 1), false));
                }
                return m;
            }
            if (s.equals("null")) return null;
            if (s.equals("true")) return Boolean.TRUE; if (s.equals("false")) return Boolean.FALSE;
            if (s.matches("-?\\d+")) { try { return Long.parseLong(s); } catch (Exception e) { return s; } }
            if (s.matches("-?\\d+\\.\\d+(E-?\\d+)?")) return Double.parseDouble(s);
            return s;
        }

        /* ---- linked structures: TreeNode / ListNode / any class with left+right or next ---- */
        static class NI { String kind; Field val, l, r, n; }
        static final int G_CAP = 120, MAX_G = 10;
        final Map<ReferenceType, NI> niCache = new HashMap<>();
        final Map<Long, Integer> gOf = new HashMap<>();                 // node object id -> structure id
        final Map<Integer, ObjectReference> gRoot = new LinkedHashMap<>();
        final Map<Integer, G> gLast = new HashMap<>();
        final Map<String, Integer> gNames = new HashMap<>();            // variable name that once pointed into a structure

        NI nodeInfo(Value v) {
            if (!(v instanceof ObjectReference o)) return null;
            ReferenceType rt = o.referenceType();
            if (niCache.containsKey(rt)) return niCache.get(rt);
            NI ni = null;
            String cn = rt.name();
            if (rt instanceof ClassType && !cn.startsWith("java.") && !cn.startsWith("javax.") && !cn.startsWith("jdk.") && !cn.startsWith("sun.") && !cn.equals("VizMain")) {
                Field l = rt.fieldByName("left"), r = rt.fieldByName("right"), n = rt.fieldByName("next");
                if (l != null && r != null && !l.isStatic() && !r.isStatic() && l.signature().startsWith("L") && r.signature().startsWith("L")) {
                    ni = new NI(); ni.kind = "tree"; ni.l = l; ni.r = r;
                    if (n != null && !n.isStatic() && n.signature().startsWith("L")) ni.n = n;
                } else if (n != null && !n.isStatic() && n.signature().startsWith("L")) { ni = new NI(); ni.kind = "list"; ni.n = n; }
                if (ni != null) {
                    for (String f : new String[]{"val", "data", "key", "value"}) { Field x = rt.fieldByName(f); if (x != null && !x.isStatic()) { ni.val = x; break; } }
                    if (ni.val == null) for (Field f : rt.visibleFields()) if (!f.isStatic() && f.signature().length() == 1) { ni.val = f; break; }
                }
            }
            niCache.put(rt, ni);
            return ni;
        }
        Object valOf(ObjectReference o, NI ni) {
            if (ni.val == null) return "•";
            try {
                Value v = o.getValue(ni.val);
                if (v == null) return null;
                if (v instanceof ObjectReference orf && !(v instanceof StringReference) && !orf.referenceType().name().startsWith("java.lang.")) return "•";
                return snapOf(v, null, 0);
            } catch (Exception e) { return "•"; }
        }
        String nodeText(ObjectReference o, NI ni) {
            if (!ni.kind.equals("list")) { Object v = valOf(o, ni); return "T(" + (v == null ? "null" : v) + ")"; }
            StringBuilder sb = new StringBuilder(); ObjectReference c = o;
            for (int i = 0; c != null && i < 12; i++) {
                NI ci = nodeInfo(c); if (ci == null || ci.n == null) break;
                if (i > 0) sb.append('→');
                Object cv = valOf(c, ci); sb.append(cv == null ? "null" : cv);
                c = c.getValue(ci.n) instanceof ObjectReference nr ? nr : null;
            }
            if (c != null) sb.append("→…");
            return sb.toString();
        }
        Long idOf(Value v) { return v instanceof ObjectReference c && nodeInfo(c) != null ? c.uniqueID() : null; }

        /** breadth-first walk from the given roots; appends one row per node: [id, val, left, right, next] */
        void collect(Collection<ObjectReference> roots, int sid, LinkedHashMap<Long, List<Object>> rows) {
            ArrayDeque<ObjectReference> q = new ArrayDeque<>(roots);
            while (!q.isEmpty() && rows.size() < G_CAP) {
                ObjectReference o = q.poll(); long id = o.uniqueID();
                if (rows.containsKey(id)) continue;
                NI ni = nodeInfo(o); if (ni == null) continue;
                gOf.putIfAbsent(id, sid);
                Value lv = ni.l == null ? null : o.getValue(ni.l), rv = ni.r == null ? null : o.getValue(ni.r), nv = ni.n == null ? null : o.getValue(ni.n);
                rows.put(id, new ArrayList<>(Arrays.asList((Object) id, valOf(o, ni), idOf(lv), idOf(rv), idOf(nv))));
                for (Value x : new Value[]{lv, rv, nv}) if (x instanceof ObjectReference c && nodeInfo(c) != null) q.add(c);
            }
        }

        void diffGraphs(Map<String, Value> vals) {
            boolean any = !gRoot.isEmpty();
            if (!any) for (Value v : vals.values()) if (v instanceof ObjectReference && nodeInfo(v) != null) { any = true; break; }
            if (!any) return;                                            // nothing node-like in this frame: stay out of the way
            Map<Integer, LinkedHashMap<Long, List<Object>>> rowsOf = new LinkedHashMap<>();
            for (var e : gRoot.entrySet()) { var rows = new LinkedHashMap<Long, List<Object>>(); collect(List.of(e.getValue()), e.getKey(), rows); rowsOf.put(e.getKey(), rows); }
            List<Object[]> ptrVars = new ArrayList<>();                  // {name, ObjectReference}
            for (var e : vals.entrySet()) {
                Value v = e.getValue(); String name = e.getKey();
                if (v == null || !(v instanceof ObjectReference o) || nodeInfo(o) == null) continue;
                ptrVars.add(new Object[]{name, o});
                if (gOf.get(o.uniqueID()) == null && gRoot.size() < MAX_G && structs.size() < MAX_STRUCTS) {
                    int sid = structs.size();
                    try { o.disableCollection(); } catch (Exception ignore) { }
                    gRoot.put(sid, o);
                    int cnt = nameCount.merge(name, 1, Integer::sum);
                    Map<String, Object> si = new LinkedHashMap<>();
                    si.put("sid", sid); si.put("name", cnt > 1 ? name + "#" + cnt : name); si.put("kind", nodeInfo(o).kind);
                    structs.add(si);
                    var rows = new LinkedHashMap<Long, List<Object>>(); collect(List.of(o), sid, rows); rowsOf.put(sid, rows);
                }
            }
            Map<Integer, Map<String, ObjectReference>> targets = new HashMap<>();
            Set<String> nullNow = new HashSet<>();
            for (Object[] pv : ptrVars) {
                String name = (String) pv[0]; ObjectReference o = (ObjectReference) pv[1]; long id = o.uniqueID();
                boolean placed = false;
                for (var re : rowsOf.entrySet()) if (re.getValue().containsKey(id)) { targets.computeIfAbsent(re.getKey(), k -> new TreeMap<>()).put(name, o); gNames.put(name, re.getKey()); placed = true; }
                if (!placed) { Integer sid = gOf.get(id); if (sid != null && rowsOf.containsKey(sid)) { targets.computeIfAbsent(sid, k -> new TreeMap<>()).put(name, o); gNames.put(name, sid); } }
            }
            for (var e : vals.entrySet()) if (e.getValue() == null && gNames.containsKey(e.getKey())) nullNow.add(e.getKey());
            for (var re : rowsOf.entrySet()) {
                int sid = re.getKey(); var rows = re.getValue();
                Map<String, ObjectReference> tg = targets.getOrDefault(sid, Map.of());
                Map<String, Long> ptrs = new TreeMap<>();
                for (var t : tg.entrySet()) { collect(List.of(t.getValue()), sid, rows); ptrs.put(t.getKey(), t.getValue().uniqueID()); }
                for (String nm : nullNow) if (gNames.get(nm) == sid && !ptrs.containsKey(nm)) ptrs.put(nm, null);
                List<List<Object>> sorted = new ArrayList<>(rows.values());
                sorted.sort(Comparator.comparingLong(r -> (Long) r.get(0)));   // stable order so a re-ordered walk is not a change
                G g = new G((String) structs.get(sid).get("kind"), sorted, ptrs);
                G prev = gLast.get(sid);
                if (prev != null && prev.key.equals(g.key)) continue;
                Map<String, Object> ev = ev("graph"); ev.put("sid", sid); ev.put("snap", g);
                if (prev == null) ev.put("silent", true);
                else {
                    boolean ptrOnly = prev.rowsKey.equals(g.rowsKey);
                    ev.put("ptrOnly", ptrOnly);
                    if (!ptrOnly) describe(prev, g, ev);
                }
                events.add(ev); gLast.put(sid, g);
            }
        }
        void describe(G prev, G g, Map<String, Object> ev) {
            Map<Long, List<Object>> old = new HashMap<>(), cur = new HashMap<>();
            for (var r : prev.rows) old.put((Long) r.get(0), r);
            for (var r : g.rows) cur.put((Long) r.get(0), r);
            String[] link = {"left", "right", "next"};
            List<Long> hl = new ArrayList<>(); List<String> parts = new ArrayList<>();
            for (var row : g.rows) {
                Long id = (Long) row.get(0); var o = old.get(id); String me = "(" + text(row.get(1)) + ")";
                if (o == null) { hl.add(id); parts.add("new node " + me); continue; }
                List<String> ch = new ArrayList<>();
                if (!Objects.equals(o.get(1), row.get(1))) ch.add(me + ".val = " + text(row.get(1)));
                for (int k = 2; k <= 4; k++) if (!Objects.equals(o.get(k), row.get(k))) {
                    var tgt = row.get(k) == null ? null : cur.get((Long) row.get(k));
                    ch.add(me + "." + link[k - 2] + " → " + (row.get(k) == null ? "null" : tgt == null ? "…" : "(" + text(tgt.get(1)) + ")"));
                }
                if (!ch.isEmpty()) { hl.add(id); parts.addAll(ch); }
            }
            ev.put("hl", hl);
            ev.put("msg", parts.isEmpty() ? "Some nodes were unlinked from the structure." : String.join("; ", parts.subList(0, Math.min(4, parts.size()))) + (parts.size() > 4 ? " …" : ""));
        }

        /* ---- detect changes to arrays / collections ---- */
        void diff(ThreadReference t) {
            try {
                StackFrame f = t.frame(0);
                LinkedHashMap<String, Value> vals = new LinkedHashMap<>();
                LinkedHashMap<String, String> ltypes = new LinkedHashMap<>();
                curVars.clear(); curWatch.clear();
                try {
                    List<LocalVariable> lvs = f.visibleVariables();
                    if (!lvs.isEmpty()) { Map<LocalVariable, Value> mv = f.getValues(lvs); for (LocalVariable lv : lvs) { vals.put(lv.name(), mv.get(lv)); ltypes.put(lv.name(), lv.typeName()); } }
                } catch (AbsentInformationException ignore) { }
                ReferenceType rt = f.location().declaringType();
                ObjectReference self = f.thisObject();
                List<Field> sf = new ArrayList<>(), inf = new ArrayList<>();
                for (Field fd : rt.fields()) { if (fd.isSynthetic()) continue; (fd.isStatic() ? sf : inf).add(fd); }
                if (!sf.isEmpty()) for (var e : rt.getValues(sf).entrySet()) vals.putIfAbsent(e.getKey().name(), e.getValue());
                if (self != null && !inf.isEmpty()) for (var e : self.getValues(inf).entrySet()) vals.putIfAbsent(e.getKey().name(), e.getValue());

                List<Object[]> cands = new ArrayList<>();      // {name, ObjectReference}
                Set<Long> seenIds = new HashSet<>();
                for (var e : vals.entrySet()) {
                    Value v = e.getValue();
                    if (v instanceof PrimitiveValue p && !(v instanceof BooleanValue)) curVars.put(e.getKey(), p.longValue());
                    if (v != null && watchable(v)) { long id = ((ObjectReference) v).uniqueID(); if (seenIds.add(id)) cands.add(new Object[]{e.getKey(), v}); }
                }
                ownedNow = new HashSet<>();
                Map<Long, Object> snaps = new HashMap<>();
                for (Object[] c : cands) { ObjectReference o = (ObjectReference) c[1]; snaps.put(o.uniqueID(), snapOf(o, t, 0)); }
                for (Object[] c : cands) {
                    String name = (String) c[0]; ObjectReference o = (ObjectReference) c[1]; long id = o.uniqueID();
                    Object snap = snaps.get(id);
                    Integer sid = sidById.get(id);
                    if (sid == null) {
                        if (ownedNow.contains(id) || structs.size() >= MAX_STRUCTS) continue;     // a row of a 2-D table etc.
                        sid = structs.size();
                        int cnt = nameCount.merge(name, 1, Integer::sum);
                        Map<String, Object> si = new LinkedHashMap<>();
                        si.put("sid", sid); si.put("name", cnt > 1 ? name + "#" + cnt : name); si.put("kind", kindOf(o));
                        structs.add(si); sidById.put(id, sid); last.put(sid, snap);
                        Map<String, Object> ev = ev("init"); ev.put("sid", sid); ev.put("snap", snap); ev.put("silent", true); events.add(ev);
                    } else {
                        List<Map<String, Object>> ws = new ArrayList<>();
                        diffSnap(sid, last.get(sid), snap, new ArrayList<>(), ws);
                        if (ws.size() > 5) {                           // bulk change (Arrays.fill, copy...) -> replace silently
                            Map<String, Object> ev = ev("init"); ev.put("sid", sid); ev.put("snap", snap); ev.put("silent", true); events.add(ev);
                        } else for (var w : ws) { w.put("reads", flush()); events.add(w); }
                        last.put(sid, snap);
                    }
                    curWatch.put(name, sid);
                }
                diffGraphs(vals);
                List<List<String>> vl = new ArrayList<>();
                for (var e : vals.entrySet()) {
                    if (vl.size() >= 24) break;
                    String nm = e.getKey(); boolean fld = !ltypes.containsKey(nm);
                    String ty = fld ? "field" : ltypes.get(nm).replaceAll("[\\w.$]*\\.", "");
                    vl.add(Arrays.asList(fld ? "this." + nm : nm, ty, varText(e.getValue(), snaps, t)));
                }
                varList = vl;
            } catch (VMDisconnectedException vd) { throw vd; }
            catch (Exception ex) { System.err.println("diff warning: " + ex); }
        }

        String varText(Value v, Map<Long, Object> snaps, ThreadReference t) {
            try {
                if (v == null) return "null";
                if (v instanceof CharValue c) return "'" + c.value() + "'";
                if (v instanceof PrimitiveValue) return text(snapOf(v, t, 0));
                if (v instanceof StringReference sr) return shorten("\"" + sr.value() + "\"", 40);
                if (v instanceof ObjectReference o) {
                    NI ni = nodeInfo(o);
                    if (ni != null) return shorten(nodeText(o, ni), 40);
                    Object sn = snaps.get(o.uniqueID());
                    if (sn == null && o.referenceType().name().startsWith("java.lang.")) sn = snapOf(o, t, 0);
                    if (sn != null) return shorten(text(sn), 60);
                    return o.referenceType().name().replaceAll("[\\w.$]*\\.", "") + "{…}";
                }
            } catch (Exception ignore) { }
            return "?";
        }

        void write(int sid, List<Object> path, Object val, boolean del, List<Map<String, Object>> out) {
            Map<String, Object> w = ev("write"); w.put("sid", sid); w.put("path", new ArrayList<>(path));
            if (del) w.put("del", true); else w.put("val", val);
            out.add(w);
        }
        void diffSnap(int sid, Object o, Object n, List<Object> path, List<Map<String, Object>> out) {
            if (o instanceof A a && n instanceof A b) {
                int m = Math.min(a.v.size(), b.v.size());
                for (int i = 0; i < m; i++) {
                    Object x = a.v.get(i), y = b.v.get(i);
                    if (same(x, y)) continue;
                    path.add((long) i);
                    if (container(x) && container(y) && x.getClass() == y.getClass()) diffSnap(sid, x, y, path, out); else write(sid, path, y, false, out);
                    path.remove(path.size() - 1);
                }
                for (int i = m; i < b.v.size(); i++) { path.add((long) i); write(sid, path, b.v.get(i), false, out); path.remove(path.size() - 1); }
                for (int i = a.v.size() - 1; i >= b.v.size(); i--) { path.add((long) i); write(sid, path, null, true, out); path.remove(path.size() - 1); }
            } else if (o instanceof M a && n instanceof M b) {
                for (var e : b.v.entrySet()) {
                    Object x = a.v.get(e.getKey()), y = e.getValue();
                    if (a.v.containsKey(e.getKey()) && same(x, y)) continue;
                    path.add(e.getKey());
                    if (a.v.containsKey(e.getKey()) && container(x) && container(y) && x.getClass() == y.getClass()) diffSnap(sid, x, y, path, out); else write(sid, path, y, false, out);
                    path.remove(path.size() - 1);
                }
                for (Object k : a.v.keySet()) if (!b.v.containsKey(k)) { path.add(k); write(sid, path, null, true, out); path.remove(path.size() - 1); }
            } else if (o instanceof S a && n instanceof S b) {
                for (Object k : b.v) if (!a.v.contains(k)) { path.add(k); write(sid, path, Boolean.TRUE, false, out); path.remove(path.size() - 1); }
                for (Object k : a.v) if (!b.v.contains(k)) { path.add(k); write(sid, path, null, true, out); path.remove(path.size() - 1); }
            } else if (!same(o, n) && !path.isEmpty()) write(sid, path, n, false, out);
        }

        /* ---- which cells does this source line read? (tiny evaluator on current locals) ---- */
        static final Pattern IDX = Pattern.compile("(?<![\\w.])(?:this\\s*\\.\\s*)?([A-Za-z_]\\w*)((?:\\s*\\[[^\\[\\]]*\\])+)");
        static final Pattern CALL = Pattern.compile("(?<![\\w])(?:this\\s*\\.\\s*)?([A-Za-z_]\\w*)\\s*\\.\\s*(get|containsKey|getOrDefault|contains)\\s*\\(");

        void extractReads(String line) {
            String src = line.replaceAll("//.*$", "");
            Matcher m = IDX.matcher(src);
            while (m.find()) {
                Integer sid = curWatch.get(m.group(1)); if (sid == null) continue;
                String after = src.substring(m.end()).stripLeading();
                if ((after.startsWith("=") && !after.startsWith("==")) || after.matches("(?s)^(\\+\\+|--|[-+*/%&|^]=|<<=|>>=).*")) continue;
                List<Object> path = new ArrayList<>(); boolean ok = true;
                Matcher im = Pattern.compile("\\[([^\\[\\]]*)\\]").matcher(m.group(2));
                while (im.find()) { Long k = eval(im.group(1)); if (k == null) { ok = false; break; } path.add(k); }
                if (ok) addRead(sid, path, null);
            }
            Matcher cm = CALL.matcher(src);
            while (cm.find()) {
                Integer sid = curWatch.get(cm.group(1)); if (sid == null) continue;
                int st = cm.end(), d = 1, i = st;
                while (i < src.length() && d > 0) { char c = src.charAt(i); if (c == '(') d++; else if (c == ')') d--; else if (c == ',' && d == 1) break; i++; }
                Long k = eval(src.substring(st, Math.min(i, src.length())).replaceAll("\\)$", ""));
                if (k != null) addRead(sid, new ArrayList<>(List.of((Object) k)), cm.group(2));
            }
        }
        void addRead(int sid, List<Object> path, String via) {
            Object cur = last.get(sid); boolean exists = true;
            for (Object k : path) {
                if (cur instanceof A a) { int i = ((Long) k).intValue(); if (i < 0 || i >= a.v.size()) { exists = false; cur = null; break; } cur = a.v.get(i); }
                else if (cur instanceof M mm) { if (!mm.v.containsKey(k)) { exists = false; cur = null; break; } cur = mm.v.get(k); }
                else if (cur instanceof S ss) { exists = ss.v.contains(k); cur = exists ? Boolean.TRUE : null; break; }
                else { exists = false; cur = null; break; }
            }
            if (container(cur)) return;
            boolean found = exists && cur != null && !(cur instanceof Long l && l == -1L);
            String key = sid + "/" + path;
            if (!pendingKeys.add(key) || pending.size() >= 12) return;
            Map<String, Object> r = new LinkedHashMap<>(); r.put("sid", sid); r.put("path", path); r.put("val", cur); r.put("found", found);
            pending.add(r);
            Node top = nstack.peek(); if (top != null && found) top.found.add(text(cur));
        }

        /* tiny integer expression evaluator: + - * / % ( ) numbers, locals, name.length */
        String ex; int ep;
        Long eval(String s) {
            try { ex = s.trim(); ep = 0; long v = expr(); ws(); return ep == ex.length() ? v : null; } catch (Exception e) { return null; }
        }
        void ws() { while (ep < ex.length() && Character.isWhitespace(ex.charAt(ep))) ep++; }
        long expr() { long v = term(); for (;;) { ws(); if (ep < ex.length() && ex.charAt(ep) == '+') { ep++; v += term(); } else if (ep < ex.length() && ex.charAt(ep) == '-') { ep++; v -= term(); } else return v; } }
        long term() { long v = unary(); for (;;) { ws(); if (ep >= ex.length()) return v; char c = ex.charAt(ep);
            if (c == '*') { ep++; v *= unary(); } else if (c == '/') { ep++; v /= unary(); } else if (c == '%') { ep++; v %= unary(); } else return v; } }
        long unary() { ws(); if (ep < ex.length() && ex.charAt(ep) == '-') { ep++; return -unary(); } return primary(); }
        long primary() {
            ws(); char c = ex.charAt(ep);
            if (c == '(') { ep++; long v = expr(); ws(); if (ex.charAt(ep) != ')') throw new RuntimeException(); ep++; return v; }
            if (Character.isDigit(c)) { int s = ep; while (ep < ex.length() && Character.isDigit(ex.charAt(ep))) ep++; return Long.parseLong(ex.substring(s, ep)); }
            if (Character.isJavaIdentifierStart(c)) {
                int s = ep; while (ep < ex.length() && Character.isJavaIdentifierPart(ex.charAt(ep))) ep++;
                String id = ex.substring(s, ep); ws();
                if (ex.startsWith(".length", ep) || ex.startsWith(".size()", ep)) {
                    ep += ex.startsWith(".length", ep) ? 7 : 7;
                    Integer sid = curWatch.get(id); Object o = sid == null ? null : last.get(sid);
                    if (o instanceof A a) return a.v.size(); throw new RuntimeException();
                }
                Long v = curVars.get(id); if (v == null) throw new RuntimeException(); return v;
            }
            throw new RuntimeException();
        }

        /* ---- assemble JSON for the page ---- */
        String build() {
            Map<String, Object> res = new LinkedHashMap<>();
            Set<String> rec = new HashSet<>();
            for (Node n : nodes) for (int a = n.parent; a >= 0; a = nodes.get(a).parent) if (nodes.get(a).method.equals(n.method)) { rec.add(n.method); break; }
            Map<Long, Integer> arrUse = new HashMap<>();
            for (Node n : nodes) for (Object[] p : n.parts) if (p[1] != null) arrUse.merge((Long) p[1], 1, Integer::sum);
            List<Node> kept = new ArrayList<>();
            for (Node n : nodes) if (rec.contains(n.method)) { n.keep = true; n.newId = kept.size(); kept.add(n); }
            for (Node n : kept) {
                int a = n.parent; while (a >= 0 && !nodes.get(a).keep) a = nodes.get(a).parent;
                n.parent = a;
                if (a >= 0) nodes.get(a).keepKids.add(n.newId);
            }
            List<Object> jn = new ArrayList<>();
            for (Node n : kept) {
                n.kind = n.keepKids.isEmpty() ? (n.found.contains(n.retText) ? "hit" : "base") : "normal";
                List<String> ps = new ArrayList<>();
                for (Object[] p : n.parts) { if (p[0] == null) continue; if (p[1] != null && arrUse.get((Long) p[1]) > 1) continue; ps.add((String) p[0]); }
                n.label = n.name + "(" + String.join(",", ps) + ")";
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", n.newId); o.put("name", n.name); o.put("label", n.label);
                o.put("parent", n.parent >= 0 ? nodes.get(n.parent).newId : null);
                o.put("kids", n.keepKids); o.put("valStr", n.valStr); o.put("kind", n.kind);
                jn.add(o);
            }
            List<Object> je = new ArrayList<>();
            for (var e : events) {
                String type = (String) e.get("type");
                if (type.equals("call") || type.equals("ret")) {
                    Node n = nodes.get((Integer) e.get("nid"));
                    if (!n.keep) continue;
                    Map<String, Object> o = new LinkedHashMap<>(e); o.remove("nid"); o.put("id", n.newId);
                    if (type.equals("ret")) o.put("kind", n.kind);
                    je.add(o);
                } else {
                    if (type.equals("graph") && Boolean.TRUE.equals(e.get("ptrOnly"))) e.put("silent", !kept.isEmpty());
                    je.add(e);
                }
            }
            res.put("ok", true);
            res.put("nodes", jn); res.put("structs", structs); res.put("events", je);
            res.put("logs", logs); res.put("result", resultText); res.put("linesTruncated", linesTruncated);
            List<String> traced = new ArrayList<>(); for (Node n : kept) if (!traced.contains(n.name)) traced.add(n.name);
            res.put("traced", traced);
            String err = null;
            if (stopped) err = "Stopped early (" + stopReason + "): the run was too long to animate. Try a smaller input.";
            else if (resultText == null) err = "The program did not finish normally" + (stderr.length() > 0 ? ":\n" + stderr : ".");
            res.put("warning", err);
            return json(res);
        }
    }
}
