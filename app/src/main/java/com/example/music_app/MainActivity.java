package com.example.music_app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION = 100;

    private RecyclerView recyclerView;
    private MusicAdapter adapter;
    private List<Song> songs = new ArrayList<>();

    private MusicService musicService;
    private boolean bound = false;

    private SeekBar seekBar;
    private TextView tvMiniTitle, tvMiniArtist;
    private ImageButton btnPlayPause, btnNext, btnBack;

    private ImageButton btnShuffle;
    private boolean isShuffle = false;

    private Handler uiHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI();

        checkAndRequestPermission();
        handleIntent(getIntent());
        startSeekBarUpdater();

        Intent serviceIntent = new Intent(this, MusicService.class);
        startService(serviceIntent);
        bindService(serviceIntent, connection, BIND_AUTO_CREATE);
    }

    private void initUI() {
        recyclerView = findViewById(R.id.recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MusicAdapter(songs);
        recyclerView.setAdapter(adapter);

        // --- Mini player UI ---
        seekBar = findViewById(R.id.seekBar);
        tvMiniTitle = findViewById(R.id.tvMiniTitle);
        tvMiniArtist = findViewById(R.id.tvMiniArtist);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNext = findViewById(R.id.btnNext);

        // --- Nút quay về (previous track) ---
        btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> {
            if (!bound || musicService == null) return;
            Song current = musicService.getCurrentSong();
            if (songs.isEmpty()) return;

            int currentIndex = -1;
            if (current != null) {
                // Tìm index an toàn bằng path (tránh phụ thuộc equals())
                String curPath = current.getPath();
                for (int i = 0; i < songs.size(); i++) {
                    Song s = songs.get(i);
                    if (s != null && s.getPath() != null && s.getPath().equals(curPath)) {
                        currentIndex = i;
                        break;
                    }
                }
            }

            // Nếu không tìm thấy current (ví dụ chưa phát), lấy index 0
            if (currentIndex == -1) {
                // Nếu đang rỗng thì không làm gì
                if (songs.isEmpty()) return;
                // Phát bài cuối làm previous của "chưa có bài"
                musicService.setPlaylist(songs);
                musicService.playAt(Math.max(0, songs.size() - 1));
            } else {
                // previous with wrap-around
                int prevIndex = (currentIndex - 1 + songs.size()) % songs.size();
                musicService.playAt(prevIndex);
            }
            updateMiniPlayer();
        });

        // --- Click bài hát ---
        adapter.setOnItemClickListener(position -> {
            if (!bound || musicService == null) return;
            musicService.setPlaylist(songs);
            musicService.playAt(position);
            updateMiniPlayer();
        });

        // --- Play / Pause ---
        btnPlayPause.setOnClickListener(v -> {
            if (!bound || musicService == null) return;

            if (musicService.isPlaying()) {
                // Chỉ pause, không dừng service hay finish activity
                musicService.pause();
                btnPlayPause.setImageResource(R.drawable.ic_play);
            } else {
                Song s = musicService.getCurrentSong();
                if (s == null && !songs.isEmpty()) {
                    musicService.setPlaylist(songs);
                    musicService.playAt(0);
                } else {
                    musicService.play();
                }
                btnPlayPause.setImageResource(R.drawable.ic_pause);
            }
        });


        // --- Next ---
        btnNext.setOnClickListener(v -> {
            if (!bound || musicService == null) return;

            Song s = musicService.getCurrentSong();
            if (s == null) {
                musicService.setPlaylist(songs);
                musicService.playAt(0);
            } else {
                musicService.next();
            }
            updateMiniPlayer();
        });

        // --- Seekbar ---
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean isUser) {}
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (bound && musicService != null)
                        musicService.seekTo(seekBar.getProgress());
                }
            });
        }

        // --- Nút shuffle ---
        btnShuffle = findViewById(R.id.btnShuffle);
        btnShuffle.setOnClickListener(v -> {
            if (!bound || musicService == null || songs.isEmpty()) return;

            isShuffle = !isShuffle; // bật / tắt shuffle

            if (isShuffle) {
                // Shuffle danh sách
                List<Song> shuffled = new ArrayList<>(songs);
                java.util.Collections.shuffle(shuffled);
                musicService.setPlaylist(shuffled);
                musicService.playAt(0);
                Toast.makeText(this, "Bật phát ngẫu nhiên", Toast.LENGTH_SHORT).show();
            } else {
                // Trở về danh sách gốc
                musicService.setPlaylist(songs);
                musicService.playAt(0);
                Toast.makeText(this, "Tắt phát ngẫu nhiên", Toast.LENGTH_SHORT).show();
            }

            updateMiniPlayer();
        });

    }

    // ========= PERMISSION =========
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{ Manifest.permission.READ_MEDIA_AUDIO };
        } else {
            return new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE };
        }
    }

    private void checkAndRequestPermission() {
        String perm = getRequiredPermissions()[0];

        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_PERMISSION);
        } else {
            loadSongsFromMusicFolder();
        }
    }

    // ========= LOAD FILE MUSIC =========
    private void loadSongsFromMusicFolder() {
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);

        if (!musicDir.exists() || !musicDir.canRead()) {
            Toast.makeText(this, "Không thể đọc thư mục /Music/", Toast.LENGTH_LONG).show();
            return;
        }

        File[] files = musicDir.listFiles();
        if (files == null) return;

        long id = 1;

        for (File f : files) {
            if (f.getName().toLowerCase().matches(".*\\.(mp3|m4a|wav|aac)$")) {
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(this, Uri.fromFile(f));

                    String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

                    long duration = dur != null ? Long.parseLong(dur) : 0;

                    if (title == null || title.isEmpty()) title = f.getName();
                    if (artist == null || artist.isEmpty()) artist = "Unknown";

                    songs.add(new Song(id++, title, artist, duration, f.getAbsolutePath()));
                    mmr.release();
                } catch (Exception e) {
                    Log.e("MUSIC", "Lỗi đọc file: " + f.getName(), e);
                }
            }
        }

        adapter.notifyDataSetChanged();
    }

    // ========= OPEN WITH AUDIO FILE =========
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && data != null && bound && musicService != null) {
            musicService.setPlaylist(songs);
            musicService.playAt(0);
            updateMiniPlayer();
        }
    }

    // ========= SERVICE CONNECTION =========
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            musicService = binder.getService();
            bound = true;

            musicService.setPlaylist(songs);
            updateMiniPlayer();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            musicService = null;
        }
    };

    // ========= MINI PLAYER UI UPDATE =========
    private void updateMiniPlayer() {
        if (!bound || musicService == null) return;

        Song s = musicService.getCurrentSong();
        if (s != null && musicService.getDuration() > 0) {
            tvMiniTitle.setText(s.getTitle());
            tvMiniArtist.setText(s.getArtist());
            seekBar.setMax(musicService.getDuration());
            seekBar.setProgress(musicService.getCurrentPosition());
            btnPlayPause.setImageResource(musicService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        } else {
            tvMiniTitle.setText("No song");
            tvMiniArtist.setText("");
            seekBar.setMax(0);
            seekBar.setProgress(0);
            btnPlayPause.setImageResource(R.drawable.ic_play);
        }
    }

    // ========= SEEK BAR AUTO UPDATE =========
    private void startSeekBarUpdater() {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (bound && musicService != null && musicService.isPlaying()) {
                    seekBar.setMax(musicService.getDuration());
                    seekBar.setProgress(musicService.getCurrentPosition());
                }
                uiHandler.postDelayed(this, 500);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) {
            unbindService(connection);
            bound = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSION &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadSongsFromMusicFolder();
        } else {
            Toast.makeText(this, "Bạn phải cấp quyền để xem nhạc!", Toast.LENGTH_LONG).show();
        }
    }
}
