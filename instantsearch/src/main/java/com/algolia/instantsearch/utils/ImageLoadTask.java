package com.algolia.instantsearch.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.widget.ImageView;

import com.algolia.instantsearch.model.Errors;

import java.io.IOException;
import java.net.URL;

public class ImageLoadTask extends AsyncTask<String, Void, Bitmap> {
    private final BitmapListener listener;

    private final ImageView imageView;
    private Bitmap bitmap;
    private String url;

    public ImageLoadTask(BitmapListener listener, ImageView imageView) {
        this.listener = listener;
        this.imageView = imageView;
    }

    @Override
    protected Bitmap doInBackground(String... params) {
        if (params.length != 1) {
            throw new IllegalStateException(Errors.IMAGELOAD_INVALID_URL);
        }
        try {
            url = params[0];
            bitmap = BitmapFactory.decodeStream(new URL(url).openConnection().getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    @Override
    protected void onPostExecute(Bitmap bitmap) {
        super.onPostExecute(bitmap);
        listener.onResult(url, bitmap, imageView);
    }

    public interface BitmapListener {
        void onResult(String url, Bitmap bitmap, ImageView imageView);
    }
}
