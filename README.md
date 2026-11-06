# Recursion & DP Visualizer

Paste a solution, press **Animate**, and watch it run step by step in the browser: the call tree, the call stack,
memo / DP tables, queues, trees, linked lists and graphs, plus a debugger-style view with the current line,
variables and loop iterations.

It is meant for **learning**: recursion, top-down DP (memoization), bottom-up DP, backtracking, BFS / DFS,
and tree / linked-list problems (LeetCode style).

---

## Files

| File | What it is |
|---|---|
| `recursion_visualizer.html` | The whole web page (UI, animation engine, JavaScript mode). |
| `VizServer.java` | A small local helper that runs **Java** code and traces it. Also serves the page. |
| `my_problems/` | Problems you animated in Java mode, saved automatically as `.json` (code + Run-box inputs). |

---

## How to run

### Java mode (needs a JDK, version 11 or newer, tested with 26; a plain JRE is not enough)

From this folder:

```bash
java VizServer.java
```

Chrome opens at `http://localhost:8765` (the next free port up to 8779 is used if 8765 is busy; read the
port it prints). Use **that address**, not the HTML file opened directly.

If your terminal's default `java` is old (Java 8 gives *"Could not find or load main class VizServer.java"*),
run it with the full path to a newer JDK, for example:

```bash
~/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home/bin/java VizServer.java
```

Options: `--no-browser` (do not open Chrome), `--port=9000` (choose the port). Stop it with Ctrl+C.
After editing `VizServer.java` or the HTML, **restart the helper** to pick up the change.

### JavaScript mode (no helper needed)

Open `recursion_visualizer.html` in a browser. Set **Language** to JavaScript.

### Using it

1. Choose the language and an example, or paste your own code.
2. In the **Run** box, type the call, e.g. `fib(6)` (JS) or `new Solution().fib(6)` (Java).
   If the method returns `void`, end it with `;`.
3. Press **Animate**, then use **Step**, **Back**, **Play**, the speed slider, or the keys ← → and space.
4. Untick **step every line** to see only the key steps (calls, returns, data changes) instead of every line.

Java helpers for the Run box (LeetCode-style classes are provided automatically unless you define your own):
`tree(1,2,3,null,5)` builds a `TreeNode` from level-order values, `list(1,2,3)` builds a `ListNode` list.

---

## What it shows

- **Call tree**: one node per recursive call, coloured by state (running, waiting on a child, returned,
  memo hit). Return values appear on the node.
- **Call stack**: frames are pushed and popped with an animation, with arguments and return values.
- **Memo hits**: a call that returns a value it read from a cache is marked as a cache hit.
- **Data structures**: 1-D arrays, 2-D grids, `List`, `Map`, `Set`, queues, deques and stacks. Written cells
  turn blue, read cells turn yellow. Every add / update / remove is listed in an activity log.
- **Trees, linked lists, graphs**: node-and-pointer drawings, with pointer variables (`cur`, `node`, `head`)
  labelled on the nodes; queues / visited sets / distances for BFS and DFS.
- **Debugger view (Java)**: source with the current line highlighted, a variables table (changed values are
  highlighted), and `for` / `while` loop iteration counters.
- **Plain-English narration** for every step (PUSH, POP, MEMO HIT, STORE, WRITE, ADD, REMOVE, LINE, ...).

Built-in examples: 13 JavaScript and 15 Java (Fibonacci both ways, coin change, knapsack, LCS, subsets /
backtracking, Tower of Hanoi, tree depth / invert, linked-list reverse / merge, number of islands, min stack,
graph BFS / DFS).

---

## How it works (short)

- **JavaScript mode**: the code is rewritten in the browser to log calls and to watch arrays, objects, Maps and
  Sets, then run. Recursion is found by checking which functions call themselves.
- **Java mode**: the helper compiles your code with the JDK compiler, runs it in a separate JVM under the Java
  debugger interface (JDI), and records method entries / exits, executed lines, local variables and collection
  contents. It sends that trace to the page, which replays it step by step.
- Reads of array cells (the yellow highlights) are **inferred** from the source line being executed.

---

## Limitations

### General
- **Size limits**: about 700 calls, 6,000 key events, 30,000 executed steps, 60 tracked data structures and a
  40-second time limit per run. Larger runs are cut off with a message. Use small inputs (e.g. `fib(6)`,
  a 4×4 board, 3-4 items).
- **Line-by-line detail** stops after 15,000 lines (the key steps continue).
- It is a **teaching tool, not a profiler or full debugger**: no breakpoints, no editing values while running,
  no multi-threaded programs (only the `main` thread is traced).
- Memo / DP cells holding **0** (or `-1`, depending on your code) cannot be told apart from "not computed yet",
  so a cache hit on such a value may not be recognised.
- Cache hits are **inferred** (a call with no child calls that returns a value it read from a watched
  structure), so unusual memo styles can be labelled wrongly.

### Java mode
- Needs a **JDK** (11+; tested with 26; a JRE has no compiler) and the helper running. It does not work from a plain `file://` page.
- Only the **one pasted file** is compiled (it may contain several classes). No extra files or external
  libraries (only the JDK's own classes); `package` lines are removed. `ListNode` / `TreeNode` are added for you unless you define them.
- Only the **current method's** variables are shown in the variables table (not the callers'), plus fields.
- Only arrays and `java.util` collections (`List`, `Map`, `Set`, `Deque`, `Queue`, `Stack`, `Vector`,
  `PriorityQueue`) are drawn as data. A `StringBuilder` or a string built by concatenation shows only in the
  variables table and call labels. Other collection types fall back to a slower, less reliable path.
- **Several statements on one line** show as one step (the tracer works per line). Put each statement on its
  own line to see every step.
- A queue's `poll()` shows as an update of cell 0 followed by a removal of the last cell, because the list is
  compared by position; the step text explains that the items slid forward.
- Array **reads** are found by scanning the source line, so a line such as
  `if (c) dp[i][j] = dp[i-1][j-1] + 1;` can highlight a read that did not actually happen, and index expressions
  that contain method calls (`dp[s.charAt(i)]`) are not highlighted.
- **Space-saving DP** that uses only a few variables (`prev`, `curr`) has no table to draw; use the variables
  table instead.
- Loop counters work for `for` / `while` (including for-each). `do { } while` loops are not counted.
- Class-based problems (e.g. a `MinStack` with several operations) need the Run box to end with `;` and show
  mostly the data changes, not a recursion tree.
- Not supported: Python, C++ and other languages.

### JavaScript mode
- No debugger view (line / variables / loop counters): that part is Java only.
- Reads and writes are tracked on arrays, objects, Maps and Sets created by your code. `async` / `await`,
  generators and class-heavy code are not supported or only partly handled.
- Code is rewritten by text scanning, not a full parser, so unusual formatting or syntax can confuse it.
- Recursion through class methods or callbacks may not be recognised as recursion.

---

## Security

The Java helper runs the code you send it, so it is locked down:
it listens on `127.0.0.1` only, checks the `Host` header, and requires a random per-run token that is embedded
in the page it serves, with no CORS headers. Other websites cannot make your browser send code to it.
Still, only paste Java code you trust: it runs with your user's permissions.
Saved problems are plain files in `my_problems/`; delete them any time.
