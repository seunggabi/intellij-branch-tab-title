package seunggabi;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.openapi.wm.impl.ProjectFrameHelper;
import com.intellij.openapi.wm.impl.TitleInfoProvider;
import com.intellij.ui.content.Content;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import javax.swing.Timer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Appends `⏳|✓ <branch>` to the project window title. ⏳ comes from terminal tab titles set by the shell. */
public final class BranchTitleInfoProvider implements TitleInfoProvider {
  private static final Logger LOG = Logger.getInstance(BranchTitleInfoProvider.class);
  private static final Map<Project, String> LAST = new ConcurrentHashMap<>();
  private static final Map<Project, List<Runnable>> LISTENERS = new ConcurrentHashMap<>();

  @Override public boolean isActive(Project p) { return !branch(p).isEmpty(); }

  @Override public String getValue(Project p) {
    ensurePolling(p);
    String b = branch(p);
    return b.isEmpty() ? "" : status(p) + " " + alias(b);
  }

  @Override public String getBorderlessPrefix() { return " "; }
  @Override public String getBorderlessSuffix() { return ""; }

  // Custom title bars (Windows/Linux) call this; repaint them from the same poller
  @Override public void addUpdateListener(Project p, Disposable parent, Function1<? super TitleInfoProvider, Unit> update) {
    List<Runnable> l = LISTENERS.computeIfAbsent(p, k -> new CopyOnWriteArrayList<>());
    Runnable r = () -> update.invoke(this);
    l.add(r);
    Disposer.register(parent, () -> l.remove(r));
  }

  // addUpdateListener is never called on macOS (no custom title bar), so each project starts its own poller here.
  // Poll once a second instead of subscribing to VCS and terminal events; refresh only on change
  private void ensurePolling(Project p) {
    if (p.isDisposed() || LAST.putIfAbsent(p, "") != null) return;
    Timer t = new Timer(1000, e -> {
      if (p.isDisposed()) return;
      String v = getValue(p);
      if (v.equals(LAST.put(p, v))) return;
      for (Runnable r : LISTENERS.getOrDefault(p, List.of())) r.run();
      refreshFrameTitle(p);
    });
    t.start();
    Disposer.register(p, () -> { t.stop(); LAST.remove(p); LISTENERS.remove(p); });
  }

  // Settings → Advanced Settings → Branch Title. Empty, non-matching or invalid regex → full branch name
  private static String alias(String b) {
    String re = AdvancedSettings.getString("branch.title.regex");
    if (re.isBlank()) return b;
    try {
      Matcher m = Pattern.compile(re).matcher(b);
      return m.find() ? m.replaceFirst(AdvancedSettings.getString("branch.title.replacement")) : b;
    } catch (RuntimeException e) {
      return b;
    }
  }

  // The callback only repaints the custom header. macOS window tabs follow the frame title, and
  // ProjectFrameHelper skips title info providers there (its provider list is null without a custom title bar),
  // so recompute the frame title with all registered providers (internal API, via reflection)
  private static void refreshFrameTitle(Project p) {
    ProjectFrameHelper f = WindowManagerEx.getInstanceEx().getFrameHelper(p);
    if (f == null) return;
    try {
      Method m = ProjectFrameHelper.class.getDeclaredMethod("updateTitle", Project.class, List.class);
      m.setAccessible(true);
      m.invoke(f, p, TitleInfoProvider.EP.getExtensionList());
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.warn("frame title refresh failed", e);
    }
  }

  private static String status(Project p) {
    ToolWindow tw = ToolWindowManager.getInstance(p).getToolWindow("Terminal");
    if (tw == null) return "✓";
    for (Content c : tw.getContentManager().getContents()) {
      String n = c.getDisplayName();
      if (n != null && n.startsWith("⏳")) return "⏳";
    }
    return "✓";
  }

  // Read HEAD directly to avoid a git4idea dependency. In a worktree `.git` is a `gitdir: …` file
  private static String branch(Project p) {
    try {
      Path git = Path.of(p.getBasePath(), ".git");
      if (Files.isRegularFile(git)) {
        String s = Files.readString(git).trim();
        if (s.startsWith("gitdir:")) git = git.getParent().resolve(s.substring(7).trim());
      }
      String head = Files.readString(git.resolve("HEAD")).trim();
      return head.startsWith("ref: refs/heads/") ? head.substring(16) : head.substring(0, Math.min(7, head.length()));
    } catch (Exception e) {
      return "";
    }
  }
}
