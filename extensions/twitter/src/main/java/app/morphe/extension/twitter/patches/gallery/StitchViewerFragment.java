/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.gallery;

import android.annotation.SuppressLint;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;

import java.util.ArrayList;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.twitter.Pref;

/**
 * Tiled seamless stitch viewer for multi-image posts backed by {@link WebView}.
 *
 * <p>Renders 2..N images in a gapless CSS Flexbox strip so sliced multi-panel
 * posts read continuously without allocating a single giant {@code Bitmap}.
 */
@SuppressWarnings("deprecation")
public class StitchViewerFragment extends Fragment {
    public static final String ARG_IMAGE_URLS = "stitch_image_urls";

    private WebView webView;
    private ArrayList<String> imageUrls = new ArrayList<>();
    private boolean isHorizontal;
    private boolean isReversed;
    private boolean useOriginalQuality = true;

    private TextView directionButton;
    private TextView reverseButton;
    private TextView qualityButton;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isHorizontal = "horizontal".equals(Pref.stitchViewerDefaultDirection());
        Bundle args = getArguments();
        if (args != null) {
            ArrayList<String> passed = args.getStringArrayList(ARG_IMAGE_URLS);
            if (passed != null) {
                imageUrls = passed;
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        Context context = getActivity();
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(context);
        webView.setBackgroundColor(Color.BLACK);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setDomStorageEnabled(false);

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        if (imageUrls.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(str("piko_stitch_no_images", "No stitchable images"));
            empty.setTextColor(Color.LTGRAY);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            empty.setGravity(Gravity.CENTER);
            root.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
        } else {
            root.addView(buildControlBar(context));
            webView.loadDataWithBaseURL(
                    "https://pbs.twimg.com/",
                    buildHtml(imageUrls, isHorizontal),
                    "text/html",
                    "UTF-8",
                    null);
        }

        return root;
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroyView();
    }

    private LinearLayout buildControlBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        int padH = dpToPx(context, 8f);
        int padV = dpToPx(context, 6f);
        bar.setPadding(padH, padV, padH, padV);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xCC141414);
        pillBg.setCornerRadius(dpToPx(context, 24f));
        pillBg.setStroke(dpToPx(context, 1f), 0x33FFFFFF);
        bar.setBackground(pillBg);

        directionButton = createPillButton(context);
        reverseButton = createPillButton(context);
        qualityButton = createPillButton(context);

        directionButton.setOnClickListener(v -> {
            isHorizontal = !isHorizontal;
            updateButtons();
            applyModeInWebView();
        });
        reverseButton.setOnClickListener(v -> {
            isReversed = !isReversed;
            updateButtons();
            applyModeInWebView();
        });
        qualityButton.setOnClickListener(v -> {
            useOriginalQuality = !useOriginalQuality;
            updateButtons();
            applyQualityInWebView();
        });

        bar.addView(directionButton);
        bar.addView(reverseButton);
        bar.addView(qualityButton);
        updateButtons();

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dpToPx(context, 20f);
        bar.setLayoutParams(params);
        return bar;
    }

    private TextView createPillButton(Context context) {
        TextView btn = new TextView(context);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        int padH = dpToPx(context, 12f);
        int padV = dpToPx(context, 6f);
        btn.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int marginH = dpToPx(context, 3f);
        lp.setMargins(marginH, 0, marginH, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void updateButtons() {
        if (directionButton != null) {
            directionButton.setText(isHorizontal
                    ? str("piko_stitch_horizontal", "Horizontal")
                    : str("piko_stitch_vertical", "Vertical"));
            applyActiveBg(directionButton, false);
        }
        if (reverseButton != null) {
            reverseButton.setText(str("piko_stitch_reverse", "Reverse"));
            applyActiveBg(reverseButton, isReversed);
        }
        if (qualityButton != null) {
            qualityButton.setText(useOriginalQuality
                    ? str("piko_stitch_orig", "Original")
                    : str("piko_stitch_large", "Large"));
            applyActiveBg(qualityButton, useOriginalQuality);
        }
    }

    private void applyActiveBg(TextView button, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? 0xFF1D9BF0 : 0x26FFFFFF);
        bg.setCornerRadius(dpToPx(button.getContext(), 16f));
        button.setBackground(bg);
    }

    private void applyModeInWebView() {
        if (webView == null) {
            return;
        }
        String js = "window.setStitchMode && window.setStitchMode("
                + isHorizontal + ", " + isReversed + ");";
        webView.evaluateJavascript(js, null);
    }

    private void applyQualityInWebView() {
        if (webView == null) {
            return;
        }
        String js = "window.setStitchQuality && window.setStitchQuality("
                + (useOriginalQuality ? "'orig'" : "'large'") + ");";
        webView.evaluateJavascript(js, null);
    }

    private static String buildHtml(ArrayList<String> urls, boolean startHorizontal) {
        JSONArray jsonUrls = new JSONArray(urls);
        String initialClass = startHorizontal ? "horizontal" : "vertical";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1.0,maximum-scale=5.0,user-scalable=yes'>"
                + "<style>"
                + "*{box-sizing:border-box;margin:0;padding:0;border:0;}"
                + "html,body{background:#000;width:100%;height:100%;overflow-anchor:none;}"
                + "#strip{display:flex;font-size:0;line-height:0;background:#000;}"
                + "#strip.vertical{flex-direction:column;align-items:flex-start;width:100vw;height:auto;padding-bottom:76px;}"
                + "#strip.vertical img{display:block;width:100vw;height:auto;max-width:none;flex-shrink:0;}"
                + "#strip.horizontal{flex-direction:row;flex-wrap:nowrap;align-items:center;height:100vh;width:max-content;}"
                + "#strip.horizontal img{display:block;height:100vh;width:auto;max-width:none;flex-shrink:0;}"
                + "</style></head><body>"
                + "<div id='strip' class='" + initialClass + "'></div>"
                + "<script>"
                + "var rawUrls=" + jsonUrls.toString() + ";"
                + "var strip=document.getElementById('strip');"
                + "var horiz=" + (startHorizontal ? "true" : "false") + ";"
                + "var rev=false;"
                + "var tiles=[];"
                + "function formatUrl(u,q){"
                + "  try{"
                + "    var url=new URL(u);"
                + "    if(url.searchParams.has('name')){url.searchParams.set('name',q);return url.toString();}"
                + "  }catch(e){}"
                + "  if(/name=[^&]+/.test(u))return u.replace(/name=[^&]+/,'name='+q);"
                + "  if(/:(orig|large|medium|small)$/.test(u))return u.replace(/:(orig|large|medium|small)$/,':'+q);"
                + "  return u;"
                + "}"
                + "function relayout(){"
                + "  var dpr=window.devicePixelRatio||1;"
                + "  var vw=document.documentElement.clientWidth||window.innerWidth;"
                + "  var vh=document.documentElement.clientHeight||window.innerHeight;"
                + "  var targetW=Math.round(vw*dpr)/dpr;"
                + "  var targetH=Math.round(vh*dpr)/dpr;"
                // Overlap adjacent tiles by ~1.5 physical pixels to eliminate subpixel seams across zoom levels.
                + "  var overlap=-(1.5/dpr).toFixed(4)+'px';"
                + "  var ordered=rev?tiles.slice().reverse():tiles;"
                + "  for(var i=0;i<ordered.length;i++){"
                + "    var img=ordered[i];"
                + "    strip.appendChild(img);"
                + "    var nw=img.naturalWidth,nh=img.naturalHeight;"
                + "    if(horiz){"
                + "      img.style.height=targetH+'px';"
                + "      img.style.width=(nw>0&&nh>0)?(Math.max(1/dpr,Math.round(targetH*(nw/nh)*dpr)/dpr)+'px'):'auto';"
                + "      img.style.marginTop='0';"
                + "      img.style.marginLeft=i>0?overlap:'0';"
                + "    }else{"
                + "      img.style.width=targetW+'px';"
                + "      img.style.height=(nw>0&&nh>0)?(Math.max(1/dpr,Math.round(targetW*(nh/nw)*dpr)/dpr)+'px'):'auto';"
                + "      img.style.marginLeft='0';"
                + "      img.style.marginTop=i>0?overlap:'0';"
                + "    }"
                + "  }"
                + "}"
                + "rawUrls.forEach(function(u){"
                + "  var img=document.createElement('img');"
                + "  img.dataset.raw=u;"
                + "  img.addEventListener('load',relayout);"
                + "  img.src=formatUrl(u,'orig');"
                + "  tiles.push(img);"
                + "});"
                + "relayout();"
                + "window.addEventListener('resize',relayout);"
                + "window.setStitchMode=function(h,r){"
                + "  horiz=!!h;rev=!!r;"
                + "  strip.className=horiz?'horizontal':'vertical';"
                + "  relayout();"
                + "  window.scrollTo(0,0);"
                + "};"
                + "window.setStitchQuality=function(q){"
                + "  for(var i=0;i<tiles.length;i++){tiles[i].src=formatUrl(tiles[i].dataset.raw,q);}"
                + "};"
                + "</script></body></html>";
    }

    private static String str(String name, String fallback) {
        try {
            String value = ResourceUtils.getString(name);
            return (value != null && !value.isEmpty()) ? value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()));
    }
}
