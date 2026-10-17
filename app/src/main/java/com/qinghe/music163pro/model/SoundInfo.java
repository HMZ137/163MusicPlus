package com.qinghe.music163pro.model;

/**
 * S2：云音乐「声音」（电台节目 / djprogram）条目（搜索结果用，新接口 type=2000）。
 *
 * <p>声音本身不是可播放的音频，它携带的 {@code mainSong} 才是歌；所以额外保留
 * {@link #getMainSongId()} 与主歌信息，点击时据此构造一个可播放的 {@link Song}
 * （播放内核会按 id 去 {@code getSongUrl} 取地址）。</p>
 */
public class SoundInfo {

    private final long id;
    private final String name;
    private final String coverUrl;
    private final String djName;
    private final long mainSongId;
    private final String mainSongName;
    private final String mainSongArtist;
    private final long durationMs;

    public SoundInfo(long id, String name, String coverUrl, String djName,
                     long mainSongId, String mainSongName, String mainSongArtist, long durationMs) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.djName = djName == null ? "" : djName;
        this.mainSongId = mainSongId;
        this.mainSongName = mainSongName == null ? "" : mainSongName;
        this.mainSongArtist = mainSongArtist == null ? "" : mainSongArtist;
        this.durationMs = durationMs;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public String getDjName() {
        return djName;
    }

    public long getMainSongId() {
        return mainSongId;
    }

    public String getMainSongName() {
        return mainSongName;
    }

    public String getMainSongArtist() {
        return mainSongArtist;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
