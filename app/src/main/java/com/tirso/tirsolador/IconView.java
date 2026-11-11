package com.tirso.tirsolador;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Resolution-independent toolbar icons; labels are exposed to accessibility. */
final class IconView extends View {
    private final String icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    IconView(Context context, String icon, String label, int color) {
        super(context); this.icon = icon; setContentDescription(label);
        paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        float size = Math.min(getWidth(), getHeight()) * .51f;
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 24, size / 24);
        Path p = new Path();
        switch (icon) {
            case "search":
                canvas.drawCircle(10, 10, 6.5f, paint); canvas.drawLine(15, 15, 21, 21, paint); break;
            case "refresh":
                canvas.drawArc(new RectF(4,4,20,20), 45, 290, false, paint);
                p.moveTo(16,3); p.lineTo(20,6); p.lineTo(21,1); canvas.drawPath(p,paint); break;
            case "star":
                for (int i=0;i<10;i++) {
                    double a = -Math.PI/2 + i*Math.PI/5; float r = i%2==0 ? 10 : 4.5f;
                    float x=12+(float)Math.cos(a)*r,y=12+(float)Math.sin(a)*r;
                    if(i==0)p.moveTo(x,y);else p.lineTo(x,y);
                }
                p.close(); canvas.drawPath(p,paint); break;
            case "settings":
                for(int i=0;i<24;i++) {
                    double a=i*Math.PI/12; float r=(i%3==0 || i%3==1)?10:8;
                    float x=12+(float)Math.cos(a)*r,y=12+(float)Math.sin(a)*r;
                    if(i==0)p.moveTo(x,y);else p.lineTo(x,y);
                }
                p.close();canvas.drawPath(p,paint);canvas.drawCircle(12,12,3.2f,paint);break;
            case "sort":
                canvas.drawLine(4,5,17,5,paint);canvas.drawLine(4,11,13,11,paint);canvas.drawLine(4,17,9,17,paint);
                canvas.drawLine(19,10,19,21,paint);p.moveTo(16,18);p.lineTo(19,21);p.lineTo(22,18);canvas.drawPath(p,paint);break;
            case "history":
                canvas.drawArc(new RectF(3,3,21,21), 120, 300, false, paint);
                p.moveTo(3,4); p.lineTo(4.5f,9); p.lineTo(9,7); canvas.drawPath(p,paint);
                canvas.drawLine(12,7.5f,12,12,paint); canvas.drawLine(12,12,15.5f,14,paint); break;
            case "next":
                p.moveTo(9,5);p.lineTo(16,12);p.lineTo(9,19);canvas.drawPath(p,paint);break;
            default:
                p.moveTo(15,5);p.lineTo(8,12);p.lineTo(15,19);canvas.drawPath(p,paint);
        }
        canvas.restore();
    }
}
