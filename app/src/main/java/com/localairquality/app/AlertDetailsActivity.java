package com.localairquality.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.localairquality.app.data.AlertGuidance;
import com.localairquality.app.data.ReadingStore;
import com.localairquality.app.ui.AlertSymbols;
import java.text.DateFormat;
import java.util.Date;

/** Native scrollable text keeps detailed advice readable with larger accessibility fonts. */
public final class AlertDetailsActivity extends Activity {
    private static final int INK=0xFF1F2834, MUTED=0xFF5B6777, GREEN=0xFF177A68;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private ScrollView scroll;
    private final Runnable update=new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this,60_000); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xFFF4F7FA);
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            view.setPadding(dp(18),insets.getSystemWindowInsetTop(),dp(18),insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView back=text("‹  Back",16,GREEN,true);
        back.setGravity(Gravity.CENTER_VERTICAL); back.setMinHeight(dp(48));
        back.setContentDescription("Back to air quality"); back.setOnClickListener(view -> finish());
        root.addView(back,new LinearLayout.LayoutParams(-1,-2));
        scroll=new ScrollView(this); scroll.setFillViewport(true);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(8),0,dp(24)); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    @Override protected void onResume() { super.onResume(); update.run(); }
    @Override protected void onPause() { handler.removeCallbacks(update); super.onPause(); }

    private void render() {
        int previousScroll=scroll.getScrollY();
        var reading=ReadingStore.loadReading(this);
        var details=AlertGuidance.forReading(reading,System.currentTimeMillis());
        content.removeAllViews();
        content.addView(text("Alert details",28,INK,true));
        if (reading!=null && reading.alert.active()) {
            LinearLayout banner=new LinearLayout(this); banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(dp(14),dp(14),dp(14),dp(14));
            banner.setBackground(shape(reading.alert.level().color));
            ImageView symbol=new ImageView(this);
            symbol.setImageResource(AlertSymbols.icon(reading.alert.level()));
            symbol.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            banner.addView(symbol,new LinearLayout.LayoutParams(dp(44),dp(44)));
            TextView message=text(reading.alert.message(),17,0xFFFFFFFF,true);
            message.setPadding(dp(12),0,0,0); banner.addView(message,new LinearLayout.LayoutParams(0,-2,1));
            addBlock(banner);
            String context=reading.locationName+"\n"+reading.stationName;
            if(reading.measuredAtMillis>0) context+="\nLatest report: "+DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(reading.measuredAtMillis));
            addBlock(text(context,15,MUTED,false));
        }
        for (var section:details.sections()) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16),dp(16),dp(16),dp(16)); card.setBackground(shape(0xFFFFFFFF));
            card.addView(text(section.title(),19,INK,true));
            TextView body=text(section.text(),16,INK,false); body.setTextIsSelectable(true);
            body.setPadding(0,dp(10),0,0); body.setLineSpacing(dp(4),1.03f);
            card.addView(body); addBlock(card);
        }
        if(!details.sources().isEmpty()) {
            addBlock(text("Official guidance and sources",20,INK,true));
            content.addView(text("Plain-language adaptations for this app’s custom alert levels."
                    + " Not a diagnosis or personalised medical advice. Local authority instructions take priority.",14,MUTED,false));
            for (var source:details.sources()) {
                TextView link=text(source.title()+"  ↗",15,GREEN,false);
                link.setPadding(0,dp(8),0,dp(8)); link.setMinHeight(dp(48));
                link.setGravity(Gravity.CENTER_VERTICAL);
                link.setContentDescription("Open "+source.title()+" in your browser");
                link.setOnClickListener(view -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(source.url()))); }
                    catch(ActivityNotFoundException error) { Toast.makeText(this,"No browser available",Toast.LENGTH_SHORT).show(); }
                });
                content.addView(link,new LinearLayout.LayoutParams(-1,-2));
            }
        }
        scroll.post(() -> scroll.scrollTo(0,previousScroll));
    }
    private void addBlock(android.view.View view) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.topMargin=dp(14); content.addView(view,params);
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if(bold) view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    private GradientDrawable shape(int color) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(18));
        return drawable;
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
