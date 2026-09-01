package com.fadcam.handball;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Transparent overlay used only while choosing goal centres. */
public class GoalCalibrationOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lx=-1,ly=-1,rx=-1,ry=-1;
    public GoalCalibrationOverlay(Context c, AttributeSet a){ super(c,a); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5f); paint.setColor(Color.YELLOW); setWillNotDraw(false); }
    public void setGoals(float lx,float ly,float rx,float ry){this.lx=lx;this.ly=ly;this.rx=rx;this.ry=ry;invalidate();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);drawGoal(c,lx,ly,"L");drawGoal(c,rx,ry,"R");}
    private void drawGoal(Canvas c,float x,float y,String label){ if(x<0||y<0)return; float cx=x*getWidth(),cy=y*getHeight(),w=getWidth()*.22f,h=getHeight()*.38f; c.drawRect(cx-w/2,cy-h/2,cx+w/2,cy+h/2,paint); paint.setTextSize(36f); c.drawText(label,cx+8,cy-8,paint); }
}
