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
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int badge;
    void badge(int n){badge=n;invalidate();}
    IconView(Context context, String icon, String label, int color) {
        super(context); this.icon = icon; setContentDescription(label);
        paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(color); fill.setStyle(Paint.Style.FILL);
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
            case "users":
                canvas.drawCircle(9,8,3.6f,paint);p.moveTo(2.5f,20);p.cubicTo(2.5f,13.5f,15.5f,13.5f,15.5f,20);canvas.drawPath(p,paint);
                canvas.drawCircle(17.2f,9,2.7f,paint);Path q=new Path();q.moveTo(18,14.2f);q.cubicTo(21,14.4f,22,16.5f,22,19);canvas.drawPath(q,paint);break;
            case "cast":   // a screen with waves in its corner (send to TV / PC)
                p.moveTo(2,8); p.lineTo(2,5); p.quadTo(2,4,3,4); p.lineTo(21,4); p.quadTo(22,4,22,5); p.lineTo(22,19); p.quadTo(22,20,21,20); p.lineTo(14,20); canvas.drawPath(p,paint);
                canvas.drawArc(new RectF(-4,10,8,26), 270, 90, false, paint); canvas.drawArc(new RectF(-1,13.5f,5,19.5f), 270, 90, false, paint);
                canvas.drawCircle(2.2f,19.8f,0.6f,paint); break;
            case "play": p.moveTo(7,4); p.lineTo(19.5f,12); p.lineTo(7,20); p.close(); canvas.drawPath(p,fill); break;
            case "pause": canvas.drawRoundRect(new RectF(6,4.5f,10,19.5f),1.2f,1.2f,fill); canvas.drawRoundRect(new RectF(14,4.5f,18,19.5f),1.2f,1.2f,fill); break;
            case "ff": p.moveTo(2.5f,6); p.lineTo(11.5f,12); p.lineTo(2.5f,18); p.close(); p.moveTo(12,6); p.lineTo(21,12); p.lineTo(12,18); p.close(); canvas.drawPath(p,fill); break;
            case "camera":
                canvas.drawRoundRect(new RectF(2,7,22,20),2.5f,2.5f,paint); p.moveTo(8,7); p.lineTo(9.5f,4); p.lineTo(14.5f,4); p.lineTo(16,7); canvas.drawPath(p,paint);
                canvas.drawCircle(12,13.5f,3.6f,paint); break;
            case "save":
                canvas.drawLine(12,3,12,14.5f,paint); p.moveTo(7.5f,10); p.lineTo(12,14.5f); p.lineTo(16.5f,10); p.moveTo(4,15); p.lineTo(4,20); p.lineTo(20,20); p.lineTo(20,15); canvas.drawPath(p,paint); break;
            case "load":
                canvas.drawLine(12,14.5f,12,3,paint); p.moveTo(7.5f,7.5f); p.lineTo(12,3); p.lineTo(16.5f,7.5f); p.moveTo(4,15); p.lineTo(4,20); p.lineTo(20,20); p.lineTo(20,15); canvas.drawPath(p,paint); break;
            case "cloud":
                p.moveTo(7,19); p.lineTo(17,19); p.cubicTo(21.5f,19,22.5f,12.5f,17.8f,11.5f); p.cubicTo(17.2f,6.5f,10.5f,5,8.6f,9.6f); p.cubicTo(3.6f,9.6f,2.6f,19,7,19); canvas.drawPath(p,paint);
                Path u=new Path(); canvas.drawLine(12,16.5f,12,11.5f,paint); u.moveTo(9.6f,13.8f); u.lineTo(12,11.4f); u.lineTo(14.4f,13.8f); canvas.drawPath(u,paint); break;
            case "restart":
                canvas.drawArc(new RectF(4,4,20,20), -55, 290, false, paint);
                p.moveTo(17.8f,1.8f); p.lineTo(16.8f,6.2f); p.lineTo(12.6f,5.4f); canvas.drawPath(p,paint); break;
            case "exit":
                canvas.drawArc(new RectF(4,5,20,21), -55, 290, false, paint); canvas.drawLine(12,3,12,12,paint); break;
            case "youtube":
                canvas.drawRoundRect(new RectF(2,5,22,19),5,5,paint); p.moveTo(10,9); p.lineTo(15.5f,12); p.lineTo(10,15); p.close(); canvas.drawPath(p,fill); break;
            case "photos":
                canvas.drawRoundRect(new RectF(3,4,21,20),2.5f,2.5f,paint); canvas.drawCircle(8.5f,9.5f,1.6f,paint);
                p.moveTo(3,17); p.lineTo(9,12); p.lineTo(13,16); p.lineTo(16,13); p.lineTo(21,17.5f); canvas.drawPath(p,paint); break;
            case "tv":
                canvas.drawRoundRect(new RectF(2,4,22,17), 2, 2, paint); canvas.drawLine(8,21,16,21,paint); canvas.drawLine(12,17,12,21,paint); break;
            case "pc":
                canvas.drawRoundRect(new RectF(3,4,21,15), 1.5f, 1.5f, paint); canvas.drawLine(2,19,22,19,paint); canvas.drawLine(12,15,12,19,paint); break;
            case "next":
                p.moveTo(9,5);p.lineTo(16,12);p.lineTo(9,19);canvas.drawPath(p,paint);break;
            default:
                p.moveTo(15,5);p.lineTo(8,12);p.lineTo(15,19);canvas.drawPath(p,paint);
        }
        canvas.restore();
        if (badge > 0) {
            float r = Math.min(getWidth(), getHeight()) * .21f, cx = getWidth() * .76f, cy = getHeight() * .26f;
            Paint f = new Paint(Paint.ANTI_ALIAS_FLAG); f.setColor(0xFF22C55E); canvas.drawCircle(cx, cy, r, f);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG); t.setColor(0xFF06210F); t.setTextAlign(Paint.Align.CENTER); t.setFakeBoldText(true); t.setTextSize(r * 1.35f);
            canvas.drawText(String.valueOf(Math.min(badge, 9)), cx, cy + r * .46f, t);
        }
    }
}
