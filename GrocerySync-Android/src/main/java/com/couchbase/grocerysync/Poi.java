package com.couchbase.grocerysync;

public class Poi {

    public Double longitude;
    public Double latitude;
    public String title;
    public String category;
    public int order;

    public Poi(String title, Double longitude, Double latitude, String category, int order) {
        this.title = title;
        this.longitude = longitude;
        this.latitude = latitude;
        this.category = category;
        this.order = order;
    }

    public Poi(){}

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }
}