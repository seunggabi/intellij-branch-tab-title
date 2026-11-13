package seunggabi;

import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.content.Content;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

import javax.swing.JFrame;
import javax.swing.Timer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Appends `⏳|✓ <branch>` to the project frame title. ⏳ comes from terminal tab titles set by the shell. */
public final class BranchTitleInfoProvider implements ProjectActivity {
  @Override public Object execute(Project p, Continuation<? super Unit> c) {
    // The IDE rewrites the frame title on its own events, so poll and re-apply instead of subscribing to them.
    String[] applied = {""};
    Timer t = new Timer(1000, e -> refresh(p, applied));
    t.start();
    Disposer.register(p, t::stop);
    return Unit.INSTANCE;
  }

  private static void refresh(Project p, String[] applied) {
    if (p.isDisposed()) return;
    JFrame f = WindowManager.getInstance().getFrame(p);
    if (f == null) return;
    String b = branch(p);
    String suffix = b.isEmpty() ? "" : " — " + status(p) + " " + alias(b);
    String title = f.getTitle() == null ? "" : f.getTitle();
    String base = !applied[0].isEmpty() && title.endsWith(applied[0])
        ? title.substring(0, title.length() - applied[0].length()) : title;
    applied[0] = suffix;
    if (!title.equals(base + suffix)) f.setTitle(base + suffix);
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
