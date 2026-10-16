package com.percy.silentxp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.service.quicksettings.TileService;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int GREEN = 0xff286f5d, INK = 0xff20352f, MUTED = 0xff63746c;
    private LinearLayout root, numberRows;
    private TextView status, details, appSummary;
    private Switch toggle;
    private boolean updating;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xffeff5f1);
        getWindow().setNavigationBarColor(0xffeff5f1);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xffeff5f1);
        root = column(); root.setPadding(dp(22), dp(25), dp(22), dp(30)); scroll.addView(root);
        setContentView(scroll);
        text(root, "让重要的人联系到你", 14, GREEN);
        TextView title = text(root, "白名单静音", 30, INK); title.setTypeface(null, Typeface.BOLD);
        text(root, "开启时安静，重要来电与消息照常提醒。", 15, MUTED);

        LinearLayout card = card();
        status = text(card, "正在检查模块…", 17, INK);
        toggle = new Switch(this); toggle.setText("屏蔽声音与震动"); toggle.setTextSize(18); toggle.setPadding(0, dp(15), 0, dp(15)); card.addView(toggle);
        toggle.setEnabled(false);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            toggle.setEnabled(false);
            Config.enabled(this, checked);
            Bridge.sync(this, result -> {
                if (!result.getBoolean("ready")) {
                    Config.enabled(this, false);
                    toast("系统服务未就绪，屏蔽保持关闭");
                }
                renderStatus(result);
                refreshTile();
            });
        });
        details = text(card, "默认关闭。需要在 LSPosed 启用系统框架作用域并重启手机。", 13, MUTED);
        button(card, "添加顶部快捷开关", this::addTile);

        LinearLayout phones = card();
        heading(phones, "电话与短信白名单");
        text(phones, "同一号码同时放行来电与短信；震动遵循系统设置。", 14, MUTED);
        LinearLayout buttons = new LinearLayout(this); phones.addView(buttons);
        Button contact = button(buttons, "从通讯录选择", this::pickContact);
        Button manual = button(buttons, "添加手机号", () -> numberDialog("", ""));
        contact.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1));
        manual.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1));
        numberRows = column(); phones.addView(numberRows);

        LinearLayout apps = card();
        heading(apps, "应用白名单");
        text(apps, "允许所选应用的全部音频、通知与震动。", 14, MUTED);
        appSummary = text(apps, "", 14, GREEN);
        button(apps, "选择应用", this::appDialog);

        LinearLayout rules = card();
        heading(rules, "始终保留");
        text(rules, "闹钟声音与震动\n蓝牙与有线耳机的独立音频输出\n所有已接通电话的通话音频", 15, INK);
        text(rules, "挂断后恢复屏蔽。白名单不会调高音量、开启系统震动或绕过免打扰。", 13, MUTED);
        text(root, "第一版 · Android 14 / LSPosed\n微信联系人白名单将在后续版本加入。", 12, MUTED);
        renderNumbers(); renderApps();
    }

    @Override protected void onResume() { super.onResume(); Bridge.sync(this, this::renderStatus); }

    private void renderStatus(Bundle b) {
        if (isFinishing() || isDestroyed()) return;
        boolean ready = b.getBoolean("ready");
        updating = true;
        toggle.setChecked(ready && b.getBoolean("enabled"));
        toggle.setEnabled(ready);
        updating = false;
        status.setText(ready ? "● 系统服务已连接" : "○ 等待 LSPosed 启用");
        status.setTextColor(ready ? GREEN : MUTED);
        String failure = b.getString("error", "");
        details.setText(ready ? "屏蔽" + (b.getBoolean("enabled") ? "已开启" : "已关闭")
                + " · 已拦截音频 " + b.getLong("audio") + " 次 / 震动 " + b.getLong("vibration") + " 次"
                : "在 LSPosed 中启用本模块，勾选「系统框架」，然后重启手机。"
                + (failure.isEmpty() ? "" : "\n适配状态：" + failure));
    }

    private void addTile() {
        Bridge.sync(this, b -> {
            if (!b.getBoolean("ready")) { toast("请先在 LSPosed 启用模块并重启手机"); return; }
            getSystemService(StatusBarManager.class).requestAddTileService(
                    new ComponentName(this, SilentTile.class), "白名单静音",
                    Icon.createWithResource(this, R.drawable.ic_shield), getMainExecutor(),
                    result -> toast(result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                            ? "快捷开关已添加" : "也可以在快捷设置编辑页手动添加「白名单静音」"));
        });
    }

    private void pickContact() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 11); return;
        }
        try { startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 12); }
        catch (android.content.ActivityNotFoundException e) { toast("没有可用的通讯录选择器，请手动添加号码"); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 11 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) pickContact();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 12 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (Cursor c = getContentResolver().query(data.getData(), new String[]{
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) numberDialog(c.getString(0), c.getString(1));
        } catch (RuntimeException e) { toast("读取联系人失败，可以手动添加号码"); }
    }

    private void numberDialog(String initialNumber, String initialName) {
        LinearLayout content = column(); content.setPadding(dp(24), dp(8), dp(24), 0);
        EditText number = new EditText(this); number.setHint("手机号或电话号码"); number.setInputType(InputType.TYPE_CLASS_PHONE); number.setText(initialNumber); content.addView(number);
        EditText name = new EditText(this); name.setHint("备注（选填）"); name.setSingleLine(true); name.setText(initialName); content.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("添加白名单号码").setView(content)
                .setNegativeButton("取消", null).setPositiveButton("添加", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String raw = number.getText().toString().trim();
            String clean = Policy.normalize(raw);
            int digits = clean.replace("+", "").length();
            if (!raw.matches("[+0-9()\\s-]+") || digits < 3 || digits > 15 || !clean.matches("\\+?[0-9]+")) {
                number.setError("请输入有效号码，可带国家区号"); return;
            }
            Set<String> numbers = new HashSet<>(Config.prefs(this).getStringSet("numbers", Set.of()));
            numbers.add(clean);
            Config.prefs(this).edit().putStringSet("numbers", numbers).putString("label." + clean, name.getText().toString().trim()).commit();
            changed(); renderNumbers(); dialog.dismiss();
        }));
        dialog.show();
    }

    private void renderNumbers() {
        numberRows.removeAllViews();
        List<String> numbers = new ArrayList<>(Config.prefs(this).getStringSet("numbers", Set.of()));
        numbers.sort(String::compareTo);
        if (numbers.isEmpty()) text(numberRows, "还没有白名单号码", 14, MUTED);
        for (String number : numbers) {
            LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL); numberRows.addView(row);
            String label = Config.prefs(this).getString("label." + number, "");
            TextView value = text(row, label.isEmpty() ? number : label + "\n" + number, 15, INK);
            value.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            button(row, "移除", () -> new AlertDialog.Builder(this).setTitle("移除此号码？").setMessage(number)
                    .setNegativeButton("取消", null).setPositiveButton("移除", (d, which) -> {
                        Set<String> next = new HashSet<>(Config.prefs(this).getStringSet("numbers", Set.of())); next.remove(number);
                        Config.prefs(this).edit().putStringSet("numbers", next).remove("label." + number).commit();
                        changed(); renderNumbers();
                    }).show());
        }
    }
    private void renderApps() { appSummary.setText("已选择 " + Config.prefs(this).getStringSet("packages", Set.of()).size() + " 个应用"); }
    private void changed() { Bridge.sync(this, this::renderStatus); refreshTile(); }
    private void refreshTile() { TileService.requestListeningState(this, new ComponentName(this, SilentTile.class)); }

    private record AppEntry(String pkg, String label) { }
    private void appDialog() {
        toast("正在读取应用列表…");
        new Thread(() -> {
            List<AppEntry> entries = new ArrayList<>();
            for (ApplicationInfo info : getPackageManager().getInstalledApplications(0)) {
                if (!info.packageName.equals(getPackageName())) entries.add(new AppEntry(info.packageName, info.loadLabel(getPackageManager()).toString()));
            }
            entries.sort(Comparator.comparing(AppEntry::label, java.text.Collator.getInstance(java.util.Locale.CHINA)));
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) showApps(entries); });
        }, "application-list").start();
    }
    private void showApps(List<AppEntry> entries) {
        Set<String> selected = new HashSet<>(Config.prefs(this).getStringSet("packages", Set.of()));
        List<AppEntry> visible = new ArrayList<>(entries);
        LinearLayout panel = column(); panel.setPadding(dp(16), 0, dp(16), 0);
        EditText search = new EditText(this); search.setHint("搜索应用名称或包名"); search.setSingleLine(true); panel.addView(search);
        ListView list = new ListView(this); panel.addView(list, new LinearLayout.LayoutParams(-1, dp(380)));
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visible.size(); }
            @Override public Object getItem(int p) { return visible.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int position, View convert, ViewGroup parent) {
                AppEntry app = visible.get(position);
                CheckBox check = convert instanceof CheckBox ? (CheckBox) convert : new CheckBox(MainActivity.this);
                check.setOnCheckedChangeListener(null);
                check.setText(app.label + "\n" + app.pkg); check.setTextSize(14); check.setPadding(dp(8), dp(9), dp(8), dp(9));
                check.setChecked(selected.contains(app.pkg));
                check.setOnCheckedChangeListener((v, checked) -> { if (checked) selected.add(app.pkg); else selected.remove(app.pkg); });
                return check;
            }
        };
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s.toString().toLowerCase(java.util.Locale.ROOT); visible.clear();
                for (AppEntry e : entries) if (e.label.toLowerCase(java.util.Locale.ROOT).contains(q) || e.pkg.toLowerCase(java.util.Locale.ROOT).contains(q)) visible.add(e);
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        new AlertDialog.Builder(this).setTitle("应用白名单").setView(panel).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> { Config.prefs(this).edit().putStringSet("packages", selected).commit(); renderApps(); changed(); }).show();
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card() {
        LinearLayout c = column(); c.setPadding(dp(18), dp(14), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(18)); c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(18); root.addView(c, lp); return c;
    }
    private TextView text(LinearLayout parent, String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(5), 0, dp(5)); v.setLineSpacing(dp(3), 1); parent.addView(v); return v;
    }
    private void heading(LinearLayout p, String value) { text(p, value, 18, INK).setTypeface(null, Typeface.BOLD); }
    private Button button(LinearLayout p, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextColor(GREEN); b.setTextSize(14); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); p.addView(b); return b;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
