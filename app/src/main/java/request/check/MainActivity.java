package request.check;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
public class MainActivity extends Activity {    
    private static final String[] UA = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.6099.225 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 16; SM‑S921B Build/AP1A.251250.005) AppleWebKit/537.36 Chrome/120.0.6099.144 Mobile Safari/537.36"
    };
    private static final int REQ_OVERLAY = 1001, BTN_W = 35, BTN_H = 35, TOUCH_THRESHOLD = 5;
    private WebView webView;
    private final Set<String> allLinks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final List<LinkInfo> linkInfos = Collections.synchronizedList(new ArrayList<>());
    private WindowManager wm;
    private RelativeLayout floatBall;
    private View panelView;
    private TextView ballCount;
    private ListView linkList;
    private LinkAdapter adapter;
    private boolean isPanelOpen;
    private EditText panelInput;
    private int uaIdx;
    private ExecutorService executor;
    private TextView dragInd;
    private WindowManager.LayoutParams panelParams;
    private DisplayMetrics dm;
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;
    private static class LinkInfo {
        String time, url, size, type;
        boolean sizeFetched;
        LinkInfo(String time, String url) {
            this.time = time;
            this.url = url;
            this.type = getType(url);
            this.size = "获取中...";
        }
        private String getType(String url) {
            try {
                String seg = Uri.parse(url).getLastPathSegment();
                if (seg == null) return "other";
                int dot = seg.lastIndexOf('.');
                if (dot > 0 && dot < seg.length() - 1) {
                    String ext = seg.substring(dot + 1).toLowerCase();
                    return ext.matches("[a-z0-9]+") ? ext : "other";
                }
            } catch (Exception e) {}
            return "other";
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dm = getResources().getDisplayMetrics();
        mainHandler = new Handler(Looper.getMainLooper());
        if (!checkOverlay()) { reqOverlay(); return; }
        initPowerLock();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        executor = Executors.newFixedThreadPool(5);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (floatBall == null) createFloatBall();
        webView = createWebView();
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        handleIntent(getIntent());
    }
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }
    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) loadUrlFromIntent(data.toString());
        }
    }
    private void loadUrlFromIntent(String url) {
        allLinks.clear();
        linkInfos.clear();
        updateBall();
        updateList();
        mainHandler.post(() -> {
            if (webView != null) webView.loadUrl(url);
        });
    }
    private void initPowerLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "App:KeepAlive");
            wakeLock.acquire(10*60*1000L);
        } catch (Exception e) {}
    }
    private boolean checkOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }
    private void reqOverlay() {
        Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show();
        startActivityForResult(
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.parse("package:" + getPackageName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 
            REQ_OVERLAY
        );
    }
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY && !checkOverlay()) finish();
    }
    private WebView createWebView() {
        WebView wv = new WebView(this);
        WebSettings ws = wv.getSettings();
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
		ws.setSupportZoom(true);           // 启用缩放
		ws.setBuiltInZoomControls(true);   // 启用缩放控件
		ws.setDisplayZoomControls(false);  // 隐藏默认的缩放控件（双指缩放仍然可用）
		ws.setUseWideViewPort(true);       // 使用宽视图
		ws.setLoadWithOverviewMode(true);  // 加载时缩放到适合屏幕大小
        ws.setUserAgentString(UA[uaIdx]);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        wv.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void interceptLink(String url) { addLink(url); }
            }, "LinkInterceptor");
        wv.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    addLink(r.getUrl().toString());
                    return !r.getUrl().toString().startsWith("http");
                }
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    addLink(r.getUrl().toString());
                    return super.shouldInterceptRequest(v, r);
                }
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    injectCaptureJS();
                    keepWebViewActive();
                }
            });
        return wv;
    }
    private void injectCaptureJS() {
        String js = "(function(){var o=window.XMLHttpRequest;window.XMLHttpRequest=function(){var x=new o(),r=x.open;x.open=function(m,u){if(u&&u.startsWith('http'))window.LinkInterceptor.interceptLink(u);return r.apply(this,arguments)};return x};var f=window.fetch;window.fetch=function(){var u=arguments[0];if(u&&typeof u==='string'&&u.startsWith('http'))window.LinkInterceptor.interceptLink(u);return f.apply(this,arguments)};new MutationObserver(function(m){m.forEach(function(n){n.addedNodes.forEach(function(e){if(e.tagName==='SCRIPT'&&e.src)window.LinkInterceptor.interceptLink(e.src);if(e.tagName==='LINK'&&e.href)window.LinkInterceptor.interceptLink(e.href);if(e.tagName==='IMG'&&e.src)window.LinkInterceptor.interceptLink(e.src);if(e.tagName==='VIDEO'&&e.src)window.LinkInterceptor.interceptLink(e.src);if(e.tagName==='AUDIO'&&e.src)window.LinkInterceptor.interceptLink(e.src);if(e.tagName==='SOURCE'&&e.src)window.LinkInterceptor.interceptLink(e.src)})})}).observe(document,{childList:true,subtree:true})})();";
        mainHandler.post(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
    }
    private void keepWebViewActive() {
        mainHandler.post(() -> {
            if (webView != null) {
                webView.onResume();
                webView.resumeTimers();
            }
        });
    }
    private void toggleUA() {
        allLinks.clear();
        linkInfos.clear();
        updateBall();
        updateList();
        uaIdx = (uaIdx + 1) % UA.length;
        mainHandler.post(() -> {
            if (webView != null) {
                webView.getSettings().setUserAgentString(UA[uaIdx]);
                webView.reload();
            }
        });
        Toast.makeText(this, "UA切换为: " + (uaIdx == 0 ? "桌面端" : "移动端") + "，已清空链接", Toast.LENGTH_SHORT).show();
    }
    private void addLink(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return;
        mainHandler.post(() -> {
            if (allLinks.add(url)) {
                LinkInfo li = new LinkInfo(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()), url);
                linkInfos.add(0, li);
                updateList();
                updateBall();
                fetchSize(li);
            }
        });
    }
    private void fetchSize(LinkInfo li) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(li.url).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(5000);
                conn.connect();
                li.size = conn.getResponseCode() == 200 ? 
                    (TextUtils.isEmpty(conn.getHeaderField("Content-Length")) ? "未知" : formatSize(Long.parseLong(conn.getHeaderField("Content-Length")))) : 
                    "获取失败";
                conn.disconnect();
            } catch (Exception e) {
                li.size = "获取失败";
            } finally {
                li.sizeFetched = true;
                mainHandler.post(() -> { if (adapter != null) adapter.notifyDataSetChanged(); });
            }
        });
    }
    private String formatSize(long b) {
        return b < 1024 ? b + " B" : 
            b < 1024 * 1024 ? String.format("%.1f KB", b / 1024f) : 
            String.format("%.1f MB", b / (1024f * 1024f));
    }
    private void updateList() {
        mainHandler.post(() -> {
            if (adapter != null) {
                String kw = panelInput != null ? panelInput.getText().toString().trim() : "";
                adapter.filter(kw);
                adapter.notifyDataSetChanged();
            }
        });
    }
    private void createFloatBall() {
        if (floatBall != null && floatBall.getParent() != null) {
            try { wm.removeView(floatBall); } catch (Exception e) {}
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            dp(40), dp(40),
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(10);
        p.y = dp(300);
        floatBall = new RelativeLayout(this);
        floatBall.setLayoutParams(new ViewGroup.LayoutParams(dp(40), dp(40)));
        View bg = new View(this);
        bg.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        bg.setBackground(createShape(GradientDrawable.OVAL, "#FFFFFF", dp(2), "#2196F3", 0));
        ballCount = new TextView(this);
        RelativeLayout.LayoutParams tp = new RelativeLayout.LayoutParams(-2, -2);
        tp.addRule(RelativeLayout.CENTER_IN_PARENT);
        ballCount.setLayoutParams(tp);
        ballCount.setTextColor(Color.BLACK);
        ballCount.setTextSize(18);
        ballCount.setTypeface(null, Typeface.BOLD);
        floatBall.addView(bg);
        floatBall.addView(ballCount);
        updateBall();
        floatBall.setOnTouchListener(new DragTouchListener(p, false));
        floatBall.setOnClickListener(v -> openPanel());
        try { wm.addView(floatBall, p); } catch (Exception e) {}
    }
    private void updateBall() {
        mainHandler.post(() -> { if (ballCount != null) ballCount.setText(String.valueOf(allLinks.size())); });
    }
    private void openPanel() {
        if (isPanelOpen || floatBall == null) return;
        isPanelOpen = true;
        WindowManager.LayoutParams bp = (WindowManager.LayoutParams) floatBall.getLayoutParams();
        panelParams = new WindowManager.LayoutParams(
            getScreenW() - dp(100), getScreenH() / 2,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = bp.x;
        panelParams.y = bp.y;
        LinearLayout pl = new LinearLayout(this);
        pl.setOrientation(LinearLayout.VERTICAL);
        pl.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        pl.setBackground(createShape(GradientDrawable.RECTANGLE, "#FFFFFF", dp(1), "#E0E0E0", dp(8)));
        pl.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        topBar.setPadding(0, 0, 0, dp(4));
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        panelInput = new EditText(this);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(BTN_H), 1f);
        ip.rightMargin = dp(4);
        panelInput.setLayoutParams(ip);
        panelInput.setHint("输入链接/关键词回车");
        panelInput.setHintTextColor(Color.GRAY);
        panelInput.setTextColor(Color.BLACK);
        panelInput.setTextSize(12);
        panelInput.setSingleLine(true);
        panelInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        panelInput.setBackground(createShape(GradientDrawable.RECTANGLE, "#F5F5F5", dp(1), "#E0E0E0", dp(4)));
        panelInput.setPadding(dp(3), 0, dp(3), 0);
        panelInput.setLongClickable(true);
        panelInput.setTextIsSelectable(true);
        panelInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || 
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && 
				event.getAction() == KeyEvent.ACTION_DOWN)) {
                handleInput();
                panelInput.setText("");
                panelInput.clearFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(panelInput.getWindowToken(), 0);
                return true;
            }
            return false;
        });
        panelInput.addTextChangedListener(new TextWatcher() {
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateList(); }
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void afterTextChanged(Editable s) {}
            });
        Button uaBtn = createBtn("UA", "#F5F5F5", Color.BLACK, dp(BTN_W), dp(BTN_H), v -> toggleUA());
        Button downloadBtn = createBtn("下载", "#FF9800", Color.WHITE, dp(40), dp(BTN_H), v -> downloadVisibleLinks());
        dragInd = createTextBtn("👆", "#F5F5F5", dp(BTN_W), dp(BTN_H));
        Button closeBtn = createBtn("关闭", "#F44336", Color.WHITE, dp(40), dp(BTN_H), v -> closePanel());
        topBar.addView(panelInput);
        topBar.addView(uaBtn);
        topBar.addView(downloadBtn);
        topBar.addView(dragInd);
        topBar.addView(closeBtn);
        linkList = new ListView(this);
        linkList.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        linkList.setDividerHeight(dp(1));
        linkList.setDivider(getResources().getDrawable(android.R.color.darker_gray));
        pl.addView(topBar);
        pl.addView(linkList);
        panelView = pl;
        adapter = new LinkAdapter();
        linkList.setAdapter(adapter);
        DragTouchListener panelDragListener = new DragTouchListener(panelParams, true);
        panelView.setOnTouchListener(panelDragListener);
        dragInd.setOnTouchListener(panelDragListener);
        try {
            wm.addView(panelView, panelParams);
            floatBall.setVisibility(View.GONE);
            updateList();
        } catch (Exception e) {
            isPanelOpen = false;
        }       
    }
    private Button createBtn(String text, String bgColor, int textColor, int w, int h, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(13);
        btn.setBackground(createShape(GradientDrawable.RECTANGLE, bgColor, dp(1), "#E0E0E0", dp(4)));
        btn.setPadding(0, 0, 0, 0);
        btn.setOnClickListener(listener);
        ((LinearLayout.LayoutParams) btn.getLayoutParams()).rightMargin = dp(2);
        return btn;
    }
    private TextView createTextBtn(String text, String bgColor, int w, int h) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        tv.setText(text);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(createShape(GradientDrawable.RECTANGLE, bgColor, dp(1), "#E0E0E0", dp(4)));
        ((LinearLayout.LayoutParams) tv.getLayoutParams()).rightMargin = dp(2);
        return tv;
    }
    private void handleInput() {
        String in = panelInput.getText().toString().trim();
        if (TextUtils.isEmpty(in)) return;
        String url = extractUrl(in);
        if (!TextUtils.isEmpty(url)) {
            if (!url.startsWith("http")) url = "https://" + url;
            allLinks.clear();
            linkInfos.clear();
            updateBall();
            updateList();
            final String finalUrl = url;
            mainHandler.post(() -> {
                if (webView != null) {
                    keepWebViewActive();
                    webView.loadUrl(finalUrl);
                }
            });
        } else {
            updateList();
        }
    }
    private String extractUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Pattern urlPattern = Pattern.compile("(https?://|www\\.)[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]");
        java.util.regex.Matcher matcher = urlPattern.matcher(text);
        if (matcher.find()) {
            String found = matcher.group();
            if (found.startsWith("www.")) found = "https://" + found;
            return found.replaceAll("[\\s\"'<>]+$", "");
        }
        Pattern domainPattern = Pattern.compile("[a-zA-Z0-9][-a-zA-Z0-9]*\\.(com|cn|net|org|gov|edu|io|co|tv|me|info|biz|xyz|top|club|site|wang|vip)(\\/[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|])?");
        matcher = domainPattern.matcher(text);
        if (matcher.find()) {
            return "https://" + matcher.group().replaceAll("[\\s\"'<>]+$", "");
        }
        return null;
    }
    private void downloadVisibleLinks() {
        if (adapter == null || adapter.getCount() == 0) {
            Toast.makeText(this, "没有可下载的链接", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            LinkInfo li = adapter.getItem(i);
            if (li != null && !TextUtils.isEmpty(li.url)) {
                sendToDownloader(li.url);
                count++;
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
        }
        if (count > 0) {
            Toast.makeText(this, "已发送 " + count + " 个链接到下载器", Toast.LENGTH_SHORT).show();
        }
    }
    private void sendToDownloader(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setClassName("idm.internet.download.manager.plus","idm.internet.download.manager.Downloader");
            intent.setData(Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ex) {}
        }
    }
    private class LinkAdapter extends BaseAdapter {
        private List<LinkInfo> filterList = new ArrayList<>();
        public LinkAdapter() { filterList.addAll(linkInfos); }
        public void filter(String kw) {
            filterList.clear();
            if (TextUtils.isEmpty(kw)) {
                filterList.addAll(linkInfos);
            } else {
                String lkw = kw.toLowerCase();
                synchronized (linkInfos) {
                    for (LinkInfo li : linkInfos) {
                        if (li.url.toLowerCase().contains(lkw) || li.type.toLowerCase().contains(lkw)) {
                            filterList.add(li);
                        }
                    }
                }
            }
        }
        @Override public int getCount() { return filterList.size(); }
        @Override public LinkInfo getItem(int p) { return p >= 0 && p < filterList.size() ? filterList.get(p) : null; }
        @Override public long getItemId(int p) { return p; }
        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            ViewHolder h;
            if (cv == null) {
                cv = createItemView();
                h = new ViewHolder();
                h.timeTv = cv.findViewById(1);
                h.sizeTv = cv.findViewById(2);
                h.typeTv = cv.findViewById(3);
                h.urlTv = cv.findViewById(4);
                h.getBtn = cv.findViewById(5);
                cv.setTag(h);
            } else h = (ViewHolder) cv.getTag();
            LinkInfo li = getItem(p);
            if (li != null) {
                h.timeTv.setText(li.time != null ? li.time : "");
                h.sizeTv.setText(li.size != null ? li.size : "");
                h.typeTv.setText(li.type != null ? li.type : "");
                h.urlTv.setText(li.url != null ? li.url : "");
                h.getBtn.setOnClickListener(v -> downWithIDM(li.url));
                h.getBtn.setOnLongClickListener(v -> {
                    copyToClipboard(li.url);
                    Toast.makeText(MainActivity.this, "已复制：" + (li.url.length() > 30 ? li.url.substring(0, 30) + "..." : li.url), Toast.LENGTH_SHORT).show();
                    return true;
                });
                h.typeTv.setOnClickListener(v -> {
                    if (!"other".equals(li.type) && panelInput != null) {
                        String currentText = panelInput.getText().toString().trim();
                        panelInput.setText(currentText.equals(li.type) ? "" : li.type);
                        updateList();
                    }
                });
            }
            return cv;
        }
        private View createItemView() {
            LinearLayout il = new LinearLayout(MainActivity.this);
            il.setOrientation(LinearLayout.VERTICAL);
            il.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            il.setPadding(dp(4), dp(4), dp(4), dp(4));
            LinearLayout r1 = new LinearLayout(MainActivity.this);
            r1.setOrientation(LinearLayout.HORIZONTAL);
            r1.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            r1.setGravity(Gravity.CENTER_VERTICAL);
            TextView t1 = new TextView(MainActivity.this);
            t1.setId(1);
            t1.setLayoutParams(new LinearLayout.LayoutParams(dp(80), -2));
            t1.setTextColor(Color.BLACK);
            t1.setTextSize(10);
            t1.setSingleLine(true);
            TextView t2 = new TextView(MainActivity.this);
            t2.setId(2);
            t2.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            t2.setTextColor(Color.GRAY);
            t2.setTextSize(10);
            t2.setPadding(dp(4), 0, 0, 0);
            r1.addView(t1);
            r1.addView(t2);
            LinearLayout r2 = new LinearLayout(MainActivity.this);
            r2.setOrientation(LinearLayout.HORIZONTAL);
            r2.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            r2.setGravity(Gravity.CENTER_VERTICAL);
            r2.setPadding(0, dp(2), 0, 0);
            TextView t3 = new TextView(MainActivity.this);
            t3.setId(3);
            t3.setLayoutParams(new LinearLayout.LayoutParams(dp(30), -2));
            t3.setTextColor(Color.parseColor("#2196F3"));
            t3.setTextSize(10);
            t3.setGravity(Gravity.CENTER);
            t3.setPadding(dp(2), dp(1), dp(2), dp(1));
            t3.setBackground(createShape(GradientDrawable.RECTANGLE, "#E3F2FD", dp(1), "#BBDEFB", dp(2)));
            t3.setClickable(true);
            HorizontalScrollView hsv = new HorizontalScrollView(MainActivity.this);
            hsv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            hsv.setHorizontalScrollBarEnabled(false);
            TextView t4 = new TextView(MainActivity.this);
            t4.setId(4);
            t4.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            t4.setTextColor(Color.GRAY);
            t4.setTextSize(10);
            t4.setSingleLine(true);
            hsv.addView(t4);
            Button b = new Button(MainActivity.this);
            b.setId(5);
            b.setLayoutParams(new LinearLayout.LayoutParams(dp(35), dp(25)));
            b.setText("获取");
            b.setTextColor(Color.WHITE);
            b.setTextSize(13);
            b.setBackground(createShape(GradientDrawable.RECTANGLE, "#4CAF50", 0, "#00000000", dp(4)));
            b.setPadding(0, 0, 0, 0);
            b.setLongClickable(true);
            r2.addView(t3);
            r2.addView(hsv);
            r2.addView(b);
            il.addView(r1);
            il.addView(r2);
            return il;
        }
        class ViewHolder { 
            TextView timeTv, sizeTv, typeTv, urlTv; 
            Button getBtn; 
        }
    }
    private void downWithIDM(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setClassName("idm.internet.download.manager.plus", "idm.internet.download.manager.MainActivity");
            i.setData(Uri.parse(url));
            startActivity(i);
        } catch (Exception e) { 
            Toast.makeText(this, "未安装IDM下载器", Toast.LENGTH_SHORT).show();
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
            } catch (Exception ex) {}
        }
    }
    private void copyToClipboard(String text) {
        ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("拦截的链接", text));
    }
    private void closePanel() {
        if (!isPanelOpen || panelView == null) return;
        try { wm.removeView(panelView); } catch (Exception e) {}
        panelView = null;
        isPanelOpen = false;
        if (floatBall != null) floatBall.setVisibility(View.VISIBLE);
    }
    private GradientDrawable createShape(int shape, String bg, int sw, String sc, float cr) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(shape);
        d.setColor(Color.parseColor(bg));
        if (sw > 0) d.setStroke(sw, Color.parseColor(sc));
        if (cr > 0) d.setCornerRadius(cr);
        return d;
    }
    private int dp(int d) { return (int) (d * dm.density + 0.5f); }
    private int getScreenW() { return dm.widthPixels; }
    private int getScreenH() { return dm.heightPixels; }
    private class DragTouchListener implements View.OnTouchListener {
        private WindowManager.LayoutParams params;
        private boolean isForPanel;
        private int initialX, initialY;
        private float initialTouchX, initialTouchY;
        private boolean isDragging;
        public DragTouchListener(WindowManager.LayoutParams params, boolean isForPanel) {
            this.params = params;
            this.isForPanel = isForPanel;
        }
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams currentParams = isForPanel ? panelParams : params;
            if (currentParams == null) return false;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = currentParams.x;
                    initialY = currentParams.y;
                    initialTouchX = e.getRawX();
                    initialTouchY = e.getRawY();
                    isDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!isDragging && (Math.abs(e.getRawX() - initialTouchX) > TOUCH_THRESHOLD || 
                        Math.abs(e.getRawY() - initialTouchY) > TOUCH_THRESHOLD)) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        currentParams.x = initialX + (int) (e.getRawX() - initialTouchX);
                        currentParams.y = initialY + (int) (e.getRawY() - initialTouchY);
                        currentParams.x = Math.max(0, Math.min(currentParams.x, getScreenW() - v.getWidth()));
                        currentParams.y = Math.max(0, Math.min(currentParams.y, getScreenH() - v.getHeight()));
                        try {
                            if (isForPanel) wm.updateViewLayout(panelView, currentParams);
                            else wm.updateViewLayout(v, currentParams);
                        } catch (Exception ex) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) v.performClick();
                    return true;
            }
            return false;
        }
    }
    @Override protected void onResume() { 
        super.onResume(); 
        keepWebViewActive(); 
    }
    @Override protected void onPause() {
        super.onPause();
        if (webView != null) mainHandler.post(() -> { 
            if (webView != null) { 
                webView.onPause(); 
                webView.pauseTimers(); 
            } 
        });
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
        if (floatBall != null) try { wm.removeView(floatBall); } catch (Exception e) {}
        if (panelView != null) try { wm.removeView(panelView); } catch (Exception e) {}
        if (webView != null) { webView.destroy(); webView = null; }
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) mainHandler.post(() -> { if (webView != null) webView.goBack(); });
        else super.onBackPressed();
    }
}
