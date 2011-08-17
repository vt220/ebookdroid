package org.ebookdroid.core;

import org.ebookdroid.core.codec.CodecPage;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.lang.ref.SoftReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class PageTreeNode implements DecodeService.DecodeCallback {

    private Bitmap bitmap;
    private SoftReference<Bitmap> bitmapWeakReference;
    private final AtomicBoolean decodingNow = new AtomicBoolean();
    private final RectF pageSliceBounds;
    private final Page page;
    private final IViewerActivity base;
    private float childrenZoomThreshold;
    private final Matrix matrix = new Matrix();
    private boolean invalidateFlag;
    private final PageTreeNode parent;

    PageTreeNode(final IViewerActivity base, final RectF localPageSliceBounds, final Page page,
            final float childrenZoomThreshold, final PageTreeNode parent, final boolean sliceLimit) {
        this.base = base;
        this.parent = parent;
        this.pageSliceBounds = evaluatePageSliceBounds(localPageSliceBounds, parent);
        this.page = page;
        this.childrenZoomThreshold = childrenZoomThreshold;
    }

    public IViewerActivity getBase() {
        return base;
    }

    /**
     * Gets the parent node.
     *
     * @return the parent node
     */
    public PageTreeNode getParent() {
        return parent;
    }

    public RectF getPageSliceBounds() {
        return pageSliceBounds;
    }

    public void updateVisibility() {
        invalidateChildren();
        if (isKeptInMemory()) {
            if (!thresholdHit()) {
                if (getBitmap() != null && !getBitmap().isRecycled() && !invalidateFlag) {
                    restoreBitmapReference();
                } else {
                    decodePageTreeNode();
                }
            }
        }
        if (!isVisibleAndNotHiddenByChildren()) {
            stopDecodingThisNode("node hidden");
            setBitmap(null);
        }
    }

    public void invalidate() {
        invalidateChildren();
        invalidateFlag = true;
        updateVisibility();
    }

    void draw(final Canvas canvas, RectF viewRect, final PagePaint paint) {
        Rect tr = getTargetRect(viewRect);
        if (getBitmap() != null && !getBitmap().isRecycled()) {
            canvas.drawRect(tr, paint.getFillPaint());
            canvas.drawBitmap(getBitmap(), null, tr, paint.getBitmapPaint());
        }
        int brightness = getBase().getAppSettings().getBrightness();
        if (brightness < 100) {
            Paint p = new Paint();
            p.setColor(Color.BLACK);
            p.setAlpha(255 - brightness * 255 / 100);
            canvas.drawRect(tr, p);
        }
    }

    private boolean isKeptInMemory() {
        final boolean pageTreeNodeKeptInMemory = base.getDocumentController().shouldKeptInMemory(this);
        // Log.d("DocModel", "Node visibility: " + this + " -> " + pageTreeNodeVisible);
        return pageTreeNodeKeptInMemory;
    }

    private void invalidateChildren() {
        if (thresholdHit() && isKeptInMemory()) {
            childrenZoomThreshold = childrenZoomThreshold * 2;
            decodePageTreeNode();
        }
    }

    private boolean thresholdHit() {
            return base.getZoomModel().getZoom() > childrenZoomThreshold;
    }

    public Bitmap getBitmap() {
        return bitmapWeakReference != null ? bitmapWeakReference.get() : null;
    }

    private void restoreBitmapReference() {
        setBitmap(getBitmap());
    }

    private void decodePageTreeNode() {
        if (setDecodingNow(true)) {
            final int width = base.getView().getWidth();
            final float zoom = base.getZoomModel().getZoom() * page.getTargetRectScale();
            base.getDecodeService().decodePage(this, width, zoom, this);
        }
    }

    @Override
    public void decodeComplete(final CodecPage codecPage, final Bitmap bitmap) {
        base.getView().post(new Runnable() {

            @Override
            public void run() {
                setBitmap(bitmap);
                invalidateFlag = false;
                setDecodingNow(false);

                page.setAspectRatio(codecPage);

                invalidateChildren();
            }
        });
    }

    private RectF evaluatePageSliceBounds(final RectF localPageSliceBounds, final PageTreeNode parent) {
        if (parent == null) {
            return localPageSliceBounds;
        }
        final Matrix matrix = new Matrix();
        matrix.postScale(parent.pageSliceBounds.width(), parent.pageSliceBounds.height());
        matrix.postTranslate(parent.pageSliceBounds.left, parent.pageSliceBounds.top);
        final RectF sliceBounds = new RectF();
        matrix.mapRect(sliceBounds, localPageSliceBounds);
        return sliceBounds;
    }

    private void setBitmap(final Bitmap bitmap) {
        if (bitmap != null && bitmap.getWidth() == -1 && bitmap.getHeight() == -1) {
            return;
        }
        if (this.bitmap != bitmap) {
            if (this.bitmap != null) {
                this.bitmap.recycle();
            }
            if (bitmap != null) {
                bitmapWeakReference = new SoftReference<Bitmap>(bitmap);
            }
            this.bitmap = bitmap;
            ((AbstractDocumentView) base.getView()).redrawView();
        }
    }

    private boolean setDecodingNow(final boolean decodingNow) {
        if (this.decodingNow.compareAndSet(!decodingNow, decodingNow)) {
            if (decodingNow) {
                base.getDecodingProgressModel().increase();
            } else {
                base.getDecodingProgressModel().decrease();
            }
            return true;
        }
        return false;
    }

    private Rect getTargetRect(RectF viewRect) {
        matrix.reset();
        RectF bounds = new RectF(page.getBounds());
        bounds.offset(-viewRect.left, -viewRect.top);

        matrix.postScale(bounds.width() * page.getTargetRectScale(), bounds.height());
        matrix.postTranslate(bounds.left - bounds.width() * page.getTargetTranslate(), bounds.top);

        final RectF targetRectF = new RectF();
        matrix.mapRect(targetRectF, pageSliceBounds);
        return new Rect((int) targetRectF.left, (int) targetRectF.top, (int) targetRectF.right,
                (int) targetRectF.bottom);
    }

    private void stopDecodingThisNode(final String reason) {
        if (setDecodingNow(false)) {
            base.getDecodeService().stopDecoding(this, reason);
        }
    }

    private boolean isVisibleAndNotHiddenByChildren() {
        return isKeptInMemory();
    }

    public int getPageIndex() {
        return page.getIndex();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((page == null) ? 0 : page.getIndex());
        result = prime * result + ((pageSliceBounds == null) ? 0 : pageSliceBounds.hashCode());
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof PageTreeNode) {
            final PageTreeNode that = (PageTreeNode) obj;
            if (this.page == null) {
                return that.page == null;
            }
            return this.page.getIndex() == that.getPageIndex() && this.pageSliceBounds.equals(that.pageSliceBounds);
        }

        return false;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder("PageTreeNode");
        buf.append("[");

        buf.append("page").append("=").append(page.getIndex());
        buf.append(", ");
        buf.append("rect").append("=").append(this.pageSliceBounds);
        buf.append(", ");
        buf.append("hasBitmap").append("=").append(getBitmap() != null);

        buf.append("]");
        return buf.toString();
    }

    public int getDocumentPageIndex() {
        return page.getDocumentPageIndex();
    }

}
