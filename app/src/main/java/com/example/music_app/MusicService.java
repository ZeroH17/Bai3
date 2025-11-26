package com.example.music_app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.media.MediaMetadataCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MusicService extends Service {

    public static final String ACTION_PLAY = "com.example.musicplayer.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.example.musicplayer.ACTION_PAUSE";
    public static final String ACTION_NEXT = "com.example.musicplayer.ACTION_NEXT";
    public static final String ACTION_START_PLAYLIST = "com.example.musicplayer.ACTION_START_PLAYLIST";

    private final IBinder binder = new LocalBinder();
    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private List<Song> playlist = new ArrayList<>();
    private int currentIndex = 0;

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "music_playback_channel";

    public class LocalBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();

        mediaSession = new MediaSessionCompat(this, "MusicService");
        mediaSession.setActive(true);

        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,"Music playback", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private NotificationCompat.Action buildAction(int icon, String title, String action) {
        Intent intent = new Intent(this, MusicService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getService(this, action.hashCode(), intent, flags);
        return new NotificationCompat.Action.Builder(icon, title, pi).build();
    }

    private void startForegroundNotification() {
        Song s = getCurrentSong();
        String title = s != null ? s.getTitle() : "No song";
        String artist = s != null ? s.getArtist() : "";
        boolean isPlaying = mediaPlayer != null && mediaPlayer.isPlaying();
        int playPauseIcon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
        String playPauseText = isPlaying ? "Pause" : "Play";

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist)
                .setSmallIcon(R.drawable.ic_music_note)
                .setOnlyAlertOnce(true)
                .addAction(buildAction(R.drawable.ic_music_note, "Prev", ACTION_NEXT))
                .addAction(buildAction(playPauseIcon, playPauseText, isPlaying ? ACTION_PAUSE : ACTION_PLAY))
                .addAction(buildAction(R.drawable.ic_next, "Next", ACTION_NEXT))
                .setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(1));

        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent piOpen = PendingIntent.getActivity(this, 0, open, flags);
        nb.setContentIntent(piOpen);

        startForeground(NOTIF_ID, nb.build());
    }

    public Song getCurrentSong() {
        if (playlist == null || playlist.isEmpty() || currentIndex < 0 || currentIndex >= playlist.size()) return null;
        return playlist.get(currentIndex);
    }

    private void prepareAndStart(String path) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.reset();
                mediaPlayer.release();
            }

            mediaPlayer = new MediaPlayer();
            File f = new File(path);
            if (!f.exists()) {
                Log.e("MusicService","File không tồn tại: "+path);
                return;
            }
            mediaPlayer.setDataSource(this, Uri.fromFile(f));
            mediaPlayer.prepare();
            mediaPlayer.start();

            mediaPlayer.setOnCompletionListener(mp -> next());

            updateMediaSessionMetadata();
            updatePlaybackState(true);
            startForegroundNotification();

        } catch (Exception e) {
            Log.e("MusicService", "Lỗi phát nhạc: " + path, e);
            Toast.makeText(this, "Nhac đang phát: " + path, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateMediaSessionMetadata() {
        Song s = getCurrentSong();
        if (s == null) return;
        MediaMetadataCompat meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.getArtist())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, s.getDuration())
                .build();
        mediaSession.setMetadata(meta);
    }

    private void updatePlaybackState(boolean isPlaying) {
        long state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat pb = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_STOP)
                .setState((int) state, mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0L,1.0f)
                .build();
        mediaSession.setPlaybackState(pb);
    }

    public void setPlaylist(List<Song> list) {
        playlist.clear();
        playlist.addAll(list);
    }

    public void playAt(int index) {
        if (playlist.isEmpty()) return;
        if (index < 0) index = 0;
        if (index >= playlist.size()) index = playlist.size()-1;
        currentIndex = index;
        Song s = getCurrentSong();
        if (s != null) prepareAndStart(s.getPath());
    }

    public void play() {
        if (mediaPlayer == null) {
            Song s = getCurrentSong();
            if (s != null) prepareAndStart(s.getPath());
            return;
        }
        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            updatePlaybackState(true);
            startForegroundNotification();
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updatePlaybackState(false);
            startForegroundNotification();
        }
    }


    public void next() {
        if (playlist.isEmpty()) return;
        currentIndex = (currentIndex + 1) % playlist.size();
        playAt(currentIndex);
    }

    public int getCurrentPosition() { return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0; }
    public int getDuration() { return mediaPlayer != null ? mediaPlayer.getDuration() : 0; }
    public boolean isPlaying() { return mediaPlayer != null && mediaPlayer.isPlaying(); }
    public void seekTo(int pos) { if (mediaPlayer != null) mediaPlayer.seekTo(pos); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
    }
}
