/*
Copyright (c) 2012, Apps4Av Inc. (apps4av.com) 
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
    *     * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
    *
    *     THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package com.ds.avare.adsb;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseArray;

import com.ds.avare.position.Origin;
import com.ds.avare.shapes.DrawingContext;
import com.ds.avare.storage.Preferences;
import com.ds.avare.utils.BitmapHolder;

/**
 * 
 * @author zkhan
 *
 */
public class NexradBitmap {
    private double[] mCoords;
    private double mScaleX;
    private double mScaleY;
    private BitmapHolder mBitmap;
    
    public long timestamp;
    /**
     * 
     * @param blockNumber
     */
    private static double convertBlockNumberToLatLon(int blockNumber, double[] lonlat) {
        double lon;
        double lat;
        double scale;

        if (blockNumber < 405000) {
            int col = blockNumber % 450;
            int row = blockNumber / 450;

            lat = ((double)row + 1.0) *  4.0 / 60.0; // row + 1 as need top left lat
            lon = ((double)col + 0.0) * 48.0 / 60.0;
            scale = 1.5; // each lon bin is 1.5 min below 60 deg
        }
        else {

            blockNumber -= 405000;
            blockNumber /= 2; // blocks inc by 2

            int col = blockNumber % 225;
            int row = blockNumber / 225;

            lat = 60.0 + ((double)row + 1.0) *  4.0 / 60.0; // row + 1 as need top left lat
            lon =  0.0 + ((double)col + 0.0) * 96.0 / 60.0;
            scale = 3.0; // each lon bin is 3 min above 60 deg
        }

        if (lon > 180) {
            lon = lon - 360;
        }
        lonlat[0] = lon;
        lonlat[1] = lat;
        return scale;

    }

    /**
     * 
     * @param data
     * @param block
     */
    public NexradBitmap(long time, int[] data, int block, boolean conus, int cols, int rows) {
       
        timestamp = System.currentTimeMillis();
        mCoords = new double[2];
        
        double scale = convertBlockNumberToLatLon(block, mCoords);

        /*
         * CONUS times 5
         */
        if(conus) {
            mScaleX = scale * 5;
            mScaleY = 5;
        }
        else {
            mScaleX = scale;
            mScaleY = 1;
        }

        /*
         * If empty block, do not waste bitmap memory
         */
        if(null == data) {
            return;        
        }
        else if(data.length < cols * rows) {
            return;            
        }
        mBitmap = new BitmapHolder(cols, rows); // this creates a MUTABLE bitmap
        for(int row = 0; row < rows; row++) {
            for(int col = 0; col < cols; col++) {
                mBitmap.getBitmap().setPixel(col, row, data[col + row * cols]);
            }
        }
    }
    
    /**
     * 
     */
    public void discard() {
        if(mBitmap != null) {
            mBitmap.recycle();
            mBitmap = null;
        }
    }
    
    /**
     * 
     * @return
     */
    public double getLatTopLeft() {
        return mCoords[1];
    }
    
    /**
     * 
     * @return
     */
    public double getLonTopLeft() {
        return mCoords[0];
    }

    /**
     *
     * @return
     */
    public double getLatBottomRight() {
        return mCoords[1] - mScaleY * mBitmap.getHeight() / 60.0;
    }

    /**
     *
     * @return
     */
    public double getLonBottomRight() {
        return mCoords[0] + mScaleX * mBitmap.getWidth() / 60.0;
    }

    /**
     * 
     * @return
     */
    public BitmapHolder getBitmap() {
        return mBitmap;
    }


    /**
     * Draw on map screen
     */
    public void drawOne(Canvas canvas, Paint paint, Origin origin, Preferences pref) {
        if(null == mBitmap) {
            return;
        }

        /*
         * draw them scaled.
         */
        float x0 = (float)origin.getOffsetX(getLonTopLeft());
        float y0 = (float)origin.getOffsetY(getLatTopLeft());
        float x1 = (float)origin.getOffsetX(getLonBottomRight());
        float y1 = (float)origin.getOffsetY(getLatBottomRight());

        float scalex = (x1 - x0) / mBitmap.getWidth();
        float scaley = (y1 - y0) / mBitmap.getHeight();

        mBitmap.getTransform().setScale(scalex, scaley);
        mBitmap.getTransform().postTranslate(x0, y0);
        if(mBitmap.getBitmap() != null) {
            paint.setAlpha(pref.showLayer());
            canvas.drawBitmap(mBitmap.getBitmap(), mBitmap.getTransform(), paint);
            paint.setAlpha(255);
        }

    }


    /**
     *
     * @param ctx
     * @param nexrad
     * @param conus
     * @param shouldDraw
     */
    public static void draw(DrawingContext ctx, NexradImage nexrad, NexradImageConus conus, boolean shouldDraw) {
        if(0 == ctx.pref.showLayer() || (!shouldDraw) || (!ctx.pref.useAdsbWeather())) {
            // This shows only for nexrad layer, and when adsb is used
            return;
        }

        /*
         * CONUS for large zoom out scales
         */
        if(ctx.scale.getMacroFactor() > 4) {
             if (!conus.isOld()) {
                 drawImage(conus.getImages(), ctx);
             }
        }
        /*
         * NEXRAD when zoomed in
         */
        else {
            if (!nexrad.isOld()) {
                drawImage(nexrad.getImages(), ctx);
            }
        }
    }


    /**
     * Draw block by block
     * @param bitmaps
     * @param ctx
     */
    private static void drawImage(SparseArray<NexradBitmap> bitmaps, DrawingContext ctx) {
        if (null != bitmaps) {
            // Draw all NEXRAD blocks, do not smooth for NEXRAD, it looks odd
            boolean fl = ctx.paint.isFilterBitmap();
            ctx.paint.setFilterBitmap(false);
            for (int i = 0; i < bitmaps.size(); i++) {
                int key = bitmaps.keyAt(i);
                NexradBitmap b = bitmaps.get(key);
                b.drawOne(ctx.canvas, ctx.paint, ctx.origin, ctx.pref);
            }
            ctx.paint.setFilterBitmap(fl);
        }
    }
}
