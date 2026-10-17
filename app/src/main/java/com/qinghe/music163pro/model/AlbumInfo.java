package com.qinghe.music163pro.model;

/**
 * S2：云音乐「专辑」条目（搜索结果用，新接口 type=10）。
 *
 * <p>只保留搜索列表与 {@code AlbumDetailActivity} 需要的字段：
 * {@code AlbumDetailActivity} 读的是 {@code album_id} / {@code album_name} / {@code album_cover_url}
 * 三个 extra，所以这里不再冗余别的属性。</p>
 */
public class AlbumInfo {

    private final long id;
    private final String name;
    private final String artist;
    private final String coverUrl;
    private final int trackCount;

    public AlbumInfo(long id, String name, String artist, String coverUrl, int trackCount) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.artist = artist == null ? "" : artist;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.trackCount = trackCount;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArtist() {
        return artist;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public int getTrackCount() {
        return trackCount;
    }
}
