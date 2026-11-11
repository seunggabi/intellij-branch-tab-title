package seunggabi;

import com.intellij.openapi.Disposable;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Appends `⏳|✓ <branch>` to the project window title. ⏳ comes from terminal tab titles set by the shell. */
public final class BranchTitleInfoProvider implements TitleInfoProvider {

  @Override public boolean isActive(Project p) { return !branch(p).isEmpty(); }

  @Override public String getValue(Project p) {
    String b = branch(p);
    return b.isEmpty() ? "" : status(p) + " " + alias(b);
  }

  @Override public String getBorderlessPrefix() { return " "; }
  @Override public String getBorderlessSuffix() { return ""; }

  // Poll once a second instead of subscribing to VCS and terminal events; refresh only on change
  @Override public void addUpdateListener(Project p, Disposable parent, Function1<? super TitleInfoProvider, Unit> update) {
    String[] last = {getValue(p)};
    Timer t = new Timer(1000, e -> {
      if (p.isDisposed()) return;
      String v = getValue(p);
      if (!v.equals(last[0])) {
        last[0] = v;
        update.invoke(this);
        // The callback only repaints the custom header; macOS window tabs follow the frame title, so recompute it (internal API)
        ProjectFrameHelper f = WindowManagerEx.getInstanceEx().getFrameHelper(p);
        if (f != null) f.updateTitle$intellij_platform_ide_impl(p);
      }
    });
    t.start();
    Disposer.register(parent, t::stop);
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
