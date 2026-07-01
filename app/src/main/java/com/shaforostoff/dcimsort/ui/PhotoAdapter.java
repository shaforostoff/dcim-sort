package com.shaforostoff.dcimsort.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.MediaImage;

import java.util.List;

/** Grid adapter showing photo thumbnails for a planned folder. */
public class PhotoAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<MediaImage> images;
    private final ThumbnailLoader loader;

    public PhotoAdapter(Context ctx, List<MediaImage> images, ThumbnailLoader loader) {
        this.inflater = LayoutInflater.from(ctx);
        this.images = images;
        this.loader = loader;
    }

    @Override public int getCount() { return images.size(); }
    @Override public MediaImage getItem(int position) { return images.get(position); }
    @Override public long getItemId(int position) { return images.get(position).id; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ImageView iv = (ImageView) convertView;
        if (iv == null) {
            iv = (ImageView) inflater.inflate(R.layout.item_photo, parent, false);
        }
        MediaImage img = images.get(position);
        loader.load(img, iv);
        iv.setAlpha(SelectionStore.isSelected(img.key()) ? 1f : 0.35f);
        return iv;
    }
}
