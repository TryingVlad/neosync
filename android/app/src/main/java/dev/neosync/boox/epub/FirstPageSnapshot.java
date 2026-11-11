package dev.neosync.boox.epub;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/**
 * A picture of an EPUB's first page, to use as its cover. The page is laid
 * out by an off-screen WebView, with its own stylesheets and images served
 * straight from the EPUB (nothing goes to the network). When the WebView
 * can't produce a picture, a plain title-and-author cover is drawn instead.
 *
 * Output is a baseline JPEG, which CrossPoint's decoder reads.
 */
public final class FirstPageSnapshot {

    private static final String TAG = "FirstPageSnapshot";
    /** 2:3, a usual book cover shape. */
    public static final int WIDTH = 600;
    public static final int HEIGHT = 900;
    /** CSS pixels are drawn at this zoom so text stays readable on a small tile. */
    private static final int ZOOM_PERCENT = 170;
    private static final String HOST = "https://book.neosync.invalid/";
    private static final long RENDER_TIMEOUT_S = 20;
    private static final long SETTLE_MS = 700;

    private FirstPageSnapshot() {
    }

    /** Blocks; call from a background thread. Never returns null. */
    public static byte[] render(Context context, File epub, String firstPageEntry) {
        Bitmap page = null;
        try {
            page = renderPage(context, epub, firstPageEntry);
        } catch (Exception e) {
            Log.w(TAG, "WebView render failed: " + e);
        }
        if (page == null || isBlank(page)) {
            Log.i(TAG, "Using a title cover for " + epub.getName());
            page = titleCover(EpubCover.titleAndAuthor(epub), epub.getName());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        page.compress(Bitmap.CompressFormat.JPEG, 88, out);
        page.recycle();
        return out.toByteArray();
    }

    private static Bitmap renderPage(Context context, File epub, String entry) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                WebView view = new WebView(context);
                viewRef.set(view);
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                view.setBackgroundColor(Color.WHITE);
                view.getSettings().setJavaScriptEnabled(false);
                view.getSettings().setAllowFileAccess(false);
                view.getSettings().setAllowContentAccess(false);
                view.getSettings().setBlockNetworkLoads(true);
                view.setInitialScale(ZOOM_PERCENT);
                view.setVerticalScrollBarEnabled(false);
                view.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                        return serve(epub, entry, request.getUrl().toString());
                    }

                    @Override
                    public void onPageFinished(WebView v, String url) {
                        main.postDelayed(() -> {
                            try {
                                Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
                                Canvas canvas = new Canvas(bitmap);
                                canvas.drawColor(Color.WHITE);
                                v.draw(canvas);
                                result.set(bitmap);
                            } catch (Exception e) {
                                Log.w(TAG, "draw failed: " + e);
                            }
                            done.countDown();
                        }, SETTLE_MS);
                    }
                });
                view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, WIDTH, HEIGHT);
                view.loadUrl(HOST + entry);
            } catch (Exception e) {
                Log.w(TAG, "WebView setup failed: " + e);
                done.countDown();
            }
        });
        // A stop request (JobRunner.stop interrupts) must not cut this book's
        // render short; the job stops after the book, so keep the flag.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RENDER_TIMEOUT_S);
        boolean finished = false;
        boolean interrupted = false;
        while (true) {
            try {
                finished = done.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        main.post(() -> {
            WebView view = viewRef.get();
            if (view != null) view.destroy();
        });
        return finished ? result.get() : null;
    }

    /** Serves zip entries under HOST; everything else gets an empty answer. */
    private static WebResourceResponse serve(File epub, String firstPage, String url) {
        if (!url.startsWith(HOST)) {
            return empty();
        }
        String path = url.substring(HOST.length()).split("[?#]")[0];
        try (ZipFile zip = new ZipFile(epub)) {
            String name = EpubCover.resolve("", path);
            byte[] data = EpubCover.read(zip, name);
            if (data == null) return empty();
            String mime = mimeType(name);
            if (name.equals(firstPage)) {
                data = withPageMargins(new String(data, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8);
                // Served as HTML: XHTML parsing fails on books with stray entities.
                mime = "text/html";
            }
            return new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(data));
        } catch (Exception e) {
            return empty();
        }
    }

    /** White page with a little margin, so text doesn't touch the cover's edge. */
    static String withPageMargins(String html) {
        String style = "<style>html{background:#fff !important}"
                + "body{margin:8% 7% !important;background:#fff !important}"
                + "img,svg{max-width:100% !important;height:auto}</style>";
        int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        return head >= 0 ? html.substring(0, head) + style + html.substring(head) : style + html;
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".ttf") || n.endsWith(".otf")) return "font/ttf";
        if (n.endsWith(".woff")) return "font/woff";
        return "text/html";
    }

    /** True when nothing but white was drawn (the WebView didn't paint). */
    private static boolean isBlank(Bitmap bitmap) {
        for (int y = 0; y < bitmap.getHeight(); y += 6) {
            for (int x = 0; x < bitmap.getWidth(); x += 6) {
                int p = bitmap.getPixel(x, y);
                if (Color.red(p) < 200 || Color.green(p) < 200 || Color.blue(p) < 200) return false;
            }
        }
        return true;
    }

    /** Title and author on white, for when the page itself can't be drawn. */
    static Bitmap titleCover(String[] titleAuthor, String fileName) {
        String title = titleAuthor != null && !titleAuthor[0].isEmpty() ? titleAuthor[0]
                : fileName.replaceFirst("\\.[^.]+$", "");
        String author = titleAuthor != null ? titleAuthor[1] : "";
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint frame = new Paint();
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(4);
        frame.setColor(Color.BLACK);
        canvas.drawRect(30, 30, WIDTH - 30, HEIGHT - 30, frame);

        int textWidth = WIDTH - 120;
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextSize(54);
        titlePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        StaticLayout titleLayout = StaticLayout.Builder
                .obtain(title, 0, title.length(), titlePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(7).build();
        TextPaint authorPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        authorPaint.setColor(Color.DKGRAY);
        authorPaint.setTextSize(34);
        authorPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        StaticLayout authorLayout = StaticLayout.Builder
                .obtain(author, 0, author.length(), authorPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build();

        canvas.save();
        canvas.translate(60, HEIGHT * 0.28f);
        titleLayout.draw(canvas);
        canvas.restore();
        canvas.save();
        canvas.translate(60, HEIGHT * 0.72f);
        authorLayout.draw(canvas);
        canvas.restore();
        return bitmap;
    }
}
