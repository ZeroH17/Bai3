package com.example.music_app;

public class Song {
    private long id;
    private String title;
    private String artist;
    private long duration; // ms
    private String path; // absolute path or URI string

    public Song(long id, String title, String artist, long duration, String path) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.duration = duration;
        this.path = path;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public long getDuration() { return duration; }
    public String getPath() { return path; }
}
