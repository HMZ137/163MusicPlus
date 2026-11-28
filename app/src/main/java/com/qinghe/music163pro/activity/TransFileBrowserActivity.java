package com.qinghe.music163pro.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.qinghe.music163pro.R;
import com.qinghe.music163pro.util.LanTransferServer;
import com.qinghe.music163pro.util.UiKit;
import com.qinghe.music163pro.util.WatchBackButton;
import com.qinghe.music163pro.util.WatchConfirmDialog;
import com.qinghe.music163pro.util.WatchMenuDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 局域网传歌 → 「浏览 TransFiles」（2026-11-28 需求）。
 *
 * <p>发送端传过来的「非音乐文件」都落在 {@code /sdcard/TransFiles}（音乐进 Music），
 * 手表本身没有文件管理器，传完的 APK / 文档没法打开。这里给一个极简浏览器：</p>
 * <ul>
 *   <li>目录优先排序，点目录进去、返回键退到上一级（到根目录再返回才退页）；</li>
 *   <li>点文件：按扩展名给 MIME，交给系统打开（APK 会直接拉起安装器）；</li>
 *   <li>长按文件：打开方式（选择器）/ 删除。</li>
 * </ul>
 */
public class TransFileBrowserActivity extends BaseWatchActivity {

    private final List<File> entries = new ArrayList<>();
    private BaseAdapter adapter;
    private ListView listView;
    private TextView tvPath;
    private TextView tvEmpty;
    private File root;
    private File currentDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = LanTransferServer.defaultTransFilesDir();
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        currentDir = root;

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(getResources().getColor(R.color.bg_dark));
        rootLayout.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(8));

        TextView title = new TextView(this);
        title.setText("TransFiles");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.watch_title_text_size));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dpToPx(4), 0, dpToPx(2));
        rootLayout.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        WatchBackButton.applyTitleBarInset(this, title);

        tvPath = new TextView(this);
        tvPath.setTextColor(getResources().getColor(R.color.text_secondary));
        tvPath.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        tvPath.setSingleLine(true);
        tvPath.setEllipsize(android.text.TextUtils.TruncateAt.START);
        tvPath.setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(4));
        rootLayout.addView(tvPath);

        FrameLayout listWrap = new FrameLayout(this);
        listWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listWrap.addView(listView);

        tvEmpty = new TextView(this);
        tvEmpty.setTextColor(getResources().getColor(R.color.text_secondary));
        tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setText("这里还没有文件\n用电脑浏览器传一个过来试试");
        tvEmpty.setVisibility(View.GONE);
        listWrap.addView(tvEmpty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootLayout.addView(listWrap);

        TextView hint = new TextView(this);
        hint.setText("点按打开（APK 会直接安装），长按可选打开方式或删除");
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dpToPx(4), 0, 0);
        rootLayout.addView(hint);

        setContentView(rootLayout);

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return entries.size();
            }

            @Override
            public Object getItem(int position) {
                return entries.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return buildRow(entries.get(position));
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) {
                return;
            }
            File f = entries.get(position);
            if (f.isDirectory()) {
                load(f);
            } else {
                openFile(f);
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) {
                return false;
            }
            File f = entries.get(position);
            showFileMenu(f);
            return true;
        });

        // 返回键：目录里先往上级退，退到根目录再交给系统（退出本页）
        addBackInterceptor(() -> {
            if (currentDir != null && root != null && !currentDir.equals(root)) {
                File parent = currentDir.getParentFile();
                load(parent != null && parent.getAbsolutePath().startsWith(root.getAbsolutePath())
                        ? parent : root);
                return true;
            }
            return false;
        });

        load(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从安装器 / 其它应用回来时刷新一下（可能刚删掉或新增了文件）
        load(currentDir == null ? root : currentDir);
    }

    /** 读目录并刷新列表。 */
    private void load(File dir) {
        if (dir == null || !dir.isDirectory()) {
            dir = root;
        }
        currentDir = dir;
        entries.clear();
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File f : files) {
                if (f.getName().startsWith(".")) {
                    continue;   // 隐藏文件不展示
                }
                entries.add(f);
            }
        }
        tvPath.setText(relativePath(dir));
        tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private String relativePath(File dir) {
        if (dir == null || root == null) {
            return "";
        }
        String full = dir.getAbsolutePath();
        String base = root.getAbsolutePath();
        String rel = full.startsWith(base) ? full.substring(base.length()) : full;
        rel = rel.replace('\\', '/');
        return "TransFiles" + (rel.isEmpty() ? "" : rel);
    }

    private View buildRow(File f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        row.setBackgroundResource(R.drawable.bg_card_tile);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(4);
        row.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextColor(getResources().getColor(R.color.text_primary));
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(name);

        TextView sub = new TextView(this);
        sub.setTextColor(getResources().getColor(R.color.text_secondary));
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setSingleLine(true);
        row.addView(sub);
        if (f.isDirectory()) {
            sub.setText("文件夹");
        } else {
            String ext = extensionOf(f.getName());
            sub.setText(formatSize(f.length()) + (ext.isEmpty() ? "" : " · " + ext.toUpperCase(Locale.ROOT))
                    + (isApk(f.getName()) ? " · 点按安装" : ""));
        }
        return row;
    }

    /** 用系统默认应用打开文件（APK 会拉起安装器）。 */
    private void openFile(File f) {
        try {
            Intent intent = buildViewIntent(f);
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            // 没有默认处理者时退一步给「打开方式」选择器；仍失败就明确告知
            try {
                startActivity(Intent.createChooser(buildViewIntent(f), "打开方式"));
            } catch (Exception ignored) {
                UiKit.toast(this, "没有可以打开该文件的应用");
            }
        } catch (Exception e) {
            UiKit.toast(this, "打开失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private Intent buildViewIntent(File f) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeOf(f.getName()));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private void showFileMenu(File f) {
        if (f.isDirectory()) {
            WatchMenuDialog.show(this, f.getName(), new String[]{"打开", "删除文件夹"}, which -> {
                if (which == 0) {
                    load(f);
                } else {
                    confirmDelete(f);
                }
            });
            return;
        }
        WatchMenuDialog.show(this, f.getName(),
                new String[]{"打开", "打开方式…", "删除"}, which -> {
                    if (which == 0) {
                        openFile(f);
                    } else if (which == 1) {
                        try {
                            startActivity(Intent.createChooser(buildViewIntent(f), "打开方式"));
                        } catch (Exception e) {
                            UiKit.toast(this, "没有可以打开该文件的应用");
                        }
                    } else {
                        confirmDelete(f);
                    }
                });
    }

    private void confirmDelete(File f) {
        WatchConfirmDialog.show(this, "确认删除",
                "确定删除「" + f.getName() + "」？\n删除后无法恢复。",
                () -> {
                    boolean ok = f.isDirectory() ? deleteRecursive(f) : f.delete();
                    UiKit.toast(this, ok ? "已删除" : "删除失败");
                    load(currentDir);
                }, WatchConfirmDialog.Options.defaultOptions());
    }

    private boolean deleteRecursive(File f) {
        if (f == null) {
            return false;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        return f.delete();
    }

    private static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    }

    private static boolean isApk(String name) {
        return "apk".equalsIgnoreCase(extensionOf(name));
    }

    /** 扩展名 → MIME；认不出的给 *\/*，让系统自己挑应用。 */
    private static String mimeOf(String name) {
        switch (extensionOf(name).toLowerCase(Locale.ROOT)) {
            case "apk": return "application/vnd.android.package-archive";
            case "mp3": return "audio/mpeg";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "ogg": return "audio/ogg";
            case "lrc": return "text/plain";
            case "txt": return "text/plain";
            case "json": return "application/json";
            case "pdf": return "application/pdf";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "zip": return "application/zip";
            default: return "*/*";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }
}
