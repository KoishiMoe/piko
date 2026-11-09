/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.entity;

import java.util.List;
import java.util.Collections;
import java.util.ArrayList;

import app.morphe.extension.twitter.entity.Video;
import app.morphe.extension.twitter.entity.Media;
import app.morphe.extension.crimera.PikoUtils;

// Lcom/twitter/model/core/entity/b0;
public class ExtMediaEntities extends Debug{

    private final Object obj;

    public ExtMediaEntities(Object obj) {
        super(obj);
        this.obj = obj;
    }

    public String getHighestResolution() {
        try {
            Debug fieldEntity = new Debug(super.getField("resolutionFieldName"));
            int width = (int) fieldEntity.getField("a");
            int height = (int) fieldEntity.getField("b");

            return width + "x" + height;
        } catch (Exception e) {
            PikoUtils.logger(e);
            return "";
        }
    }


    public String getImageUrl()
            throws Exception {
                // q:String
        return (String) super.getField("getThumbnailField");
    }

    private static boolean isValidImageExt(String ext) {
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("webp");
    }

    public String getImageExtension() {
        try {
            String url = this.getImageUrl();
            if (url != null) {
                // 1. 优先从 query 参数中提取 format=
                int formatIdx = url.indexOf("format=");
                if (formatIdx != -1) {
                    int start = formatIdx + 7;
                    int end = url.indexOf('&', start);
                    String fmt = (end != -1 ? url.substring(start, end) : url.substring(start)).toLowerCase();
                    if (isValidImageExt(fmt)) return fmt;
                }

                // 2. Fallback: 从路径提取后缀
                String path = url;
                int queryIndex = url.indexOf('?');
                if (queryIndex != -1) path = url.substring(0, queryIndex);
                int lastDot = path.lastIndexOf('.');
                if (lastDot != -1 && lastDot > path.lastIndexOf('/')) {
                    String ext = path.substring(lastDot + 1).toLowerCase();
                    if (isValidImageExt(ext)) return ext;
                }
            }
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return "jpg";
    }

    public String getHighResImageUrl()
            throws Exception {
        String url = this.getImageUrl();
        if (url == null || url.isEmpty()) {
            return "";
        }

        if (url.contains("?")) {
            if (url.contains("?name=") || url.contains("&name=")) {
                return url.replaceFirst("([?&])name=[^&]+", "$1name=orig");
            } else {
                return url + "&name=orig";
            }
        }

        int lastDot = url.lastIndexOf('.');
        if (lastDot == -1 || lastDot < url.lastIndexOf('/')) {
            return url + "?name=orig&format=" + this.getImageExtension();
        }

        return url + "?name=orig";
    }

    public ArrayList<Media> getVideos() throws Exception {
        ArrayList<Media> videoArrayList = new ArrayList<>();

        Object mediaVideoInfoEntityObject = super.getField("fieldname");
        if(mediaVideoInfoEntityObject==null) return videoArrayList;

        Debug mediaVideoInfoEntity = new Debug(mediaVideoInfoEntityObject);

        Object videoVariantObject = mediaVideoInfoEntity.getField("c");
        if(videoVariantObject==null) return videoArrayList;

        int type = 1;
        List videoVariant = (List) videoVariantObject;
        videoVariant.forEach(item->{
            try {
                Video videoData = new Video(item);
                String ext = videoData.getExtension();
                if(!ext.equals("m3u8")) {
                    String url = videoData.getMediaUrl();
                    String res = videoData.getResolution();
                    res = res != null ? res : this.getHighestResolution();

                    videoArrayList.add(new Media(type, url, ext, res));
                }
            } catch (Exception e) {
                PikoUtils.logger(e);
            }
        });
        // We want highest resolution first.
        Collections.reverse(videoArrayList);
        return videoArrayList;
    }

    public ArrayList<Media> getMediaList() {
        try {
            ArrayList<Media> videoList = this.getVideos();
            if (!videoList.isEmpty()) {
                return videoList;
            } else {
                ArrayList<Media> imageList = new ArrayList<>();

                int type = 0;
                String url = this.getHighResImageUrl();
                String ext = this.getImageExtension();
                String res = this.getHighestResolution();
                imageList.add(new Media(type, url, ext, res));

                return imageList;
            }
        } catch (Exception e) {
            PikoUtils.logger(e);
            return new ArrayList<>();
        }
    }

    @Override
    public String toString(){
        try{
        return "ExtMediaEntities [getImageUrl()=" + this.getImageUrl() + ", getHighResImageUrl()="
                + this.getHighResImageUrl() + ", getMediaList()=" + this.getMediaList() + "]";
        }catch(Exception e){
            PikoUtils.logger(e);
            return e.getMessage();
        }
    }

}