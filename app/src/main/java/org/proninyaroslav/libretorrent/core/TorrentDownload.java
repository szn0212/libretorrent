/*
 * Copyright (C) 2016 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proninyaroslav.libretorrent.core;

import android.content.Context;
import android.util.Log;

import com.frostwire.jlibtorrent.AnnounceEntry;
import com.frostwire.jlibtorrent.FileStorage;
import com.frostwire.jlibtorrent.PeerInfo;
import com.frostwire.jlibtorrent.Priority;
import com.frostwire.jlibtorrent.Session;
import com.frostwire.jlibtorrent.TorrentAlertAdapter;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.alerts.BlockFinishedAlert;
import com.frostwire.jlibtorrent.alerts.SaveResumeDataAlert;
import com.frostwire.jlibtorrent.alerts.StateChangedAlert;
import com.frostwire.jlibtorrent.alerts.StatsAlert;
import com.frostwire.jlibtorrent.alerts.StorageMovedAlert;
import com.frostwire.jlibtorrent.alerts.StorageMovedFailedAlert;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.frostwire.jlibtorrent.alerts.TorrentPausedAlert;
import com.frostwire.jlibtorrent.alerts.TorrentRemovedAlert;
import com.frostwire.jlibtorrent.alerts.TorrentResumedAlert;
import com.frostwire.jlibtorrent.swig.bitfield;

import org.proninyaroslav.libretorrent.core.utils.TorrentUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * This class encapsulate one stream with running torrent.
 */

public class TorrentDownload extends TorrentAlertAdapter implements TorrentDownloadInterface
{
    @SuppressWarnings("unused")
    private static final String TAG = TorrentDownload.class.getSimpleName();

    private static final long SAVE_RESUME_SYNC_TIME = 10000; /* ms */

    public static final double MAX_RATIO = 9999.;

    private Context context;
    private TorrentEngine engine;
    private TorrentHandle th;
    private Torrent torrent;
    private TorrentEngineCallback callback;
    private Set<File> incompleteFilesToRemove;
    private File parts;
    private long lastSaveResumeTime;

    public TorrentDownload(Context context,
                           TorrentEngine engine,
                           TorrentHandle handle,
                           Torrent torrent,
                           TorrentEngineCallback callback)
    {
        super(handle);

        this.context = context;
        this.engine = engine;
        this.th = handle;
        this.torrent = torrent;
        this.callback = callback;
        TorrentInfo ti = th.getTorrentInfo();
        this.parts = ti != null ? new File(torrent.getDownloadPath(), "." + ti.infoHash() + ".parts") : null;

        engine.getSession().addListener(this);
    }

    @Override
    public void blockFinished(BlockFinishedAlert alert)
    {
        if (callback != null) {
            callback.onTorrentStateChanged(torrent.getId());
        }
    }

    @Override
    public void stateChanged(StateChangedAlert alert)
    {
        if (callback != null) {
            callback.onTorrentStateChanged(torrent.getId());
        }
    }

    @Override
    public void torrentFinished(TorrentFinishedAlert alert)
    {
        if (callback != null) {
            callback.onTorrentFinished(torrent.getId());
        }

        th.saveResumeData();
    }

    @Override
    public void torrentRemoved(TorrentRemovedAlert alert)
    {
        if (callback != null) {
            callback.onTorrentRemoved(torrent.getId());
        }

        if (parts != null) {
            parts.delete();
        }

        finalCleanup(incompleteFilesToRemove);
    }

    @Override
    public void torrentPaused(TorrentPausedAlert alert)
    {
        if (callback != null) {
            callback.onTorrentPaused(torrent.getId());
        }
    }

    @Override
    public void torrentResumed(TorrentResumedAlert alert)
    {
        if (callback != null) {
            callback.onTorrentResumed(torrent.getId());
        }
    }

    @Override
    public void stats(StatsAlert alert)
    {
        if (callback != null) {
            callback.onTorrentStateChanged(torrent.getId());
        }
    }

    /*
     * Generate fast-resume data for the torrent, see libtorrent documentation
     */

    @Override
    public void saveResumeData(SaveResumeDataAlert alert)
    {
        long now = System.currentTimeMillis();
        final TorrentStatus status = th.getStatus();

        boolean forceSerialization = status.isFinished() || status.isPaused();
        if (forceSerialization || (now - lastSaveResumeTime) >= SAVE_RESUME_SYNC_TIME) {
            lastSaveResumeTime = now;
        } else {
            /* Skip, too fast, see SAVE_RESUME_SYNC_TIME */
            return;
        }

        try {
            TorrentUtils.saveResumeData(context, torrent.getId(), alert.resumeData().bencode());

        } catch (Exception e) {
            Log.e(TAG, "Error saving resume data of " + torrent + ":");
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    @Override
    public void storageMoved(StorageMovedAlert alert)
    {
        th.saveResumeData();

        if (callback != null) {
            callback.onTorrentMoved(torrent.getId(), true);
        }
    }

    @Override
    public void storageMovedFailed(StorageMovedFailedAlert alert)
    {
        th.saveResumeData();

        if (callback != null) {
            callback.onTorrentMoved(torrent.getId(), false);
        }
    }

    @Override
    public void pause()
    {
        if (th == null) {
            return;
        }

        th.setAutoManaged(false);
        th.pause();
        th.saveResumeData();
    }

    @Override
    public void resume()
    {
        if (th == null) {
            return;
        }

        th.setAutoManaged(true);
        th.resume();
        th.saveResumeData();
    }

    @Override
    public void setTorrent(Torrent torrent)
    {
        this.torrent = torrent;
    }

    @Override
    public Torrent getTorrent()
    {
        return torrent;
    }

    @Override
    public int getProgress()
    {
        float fp = th.getStatus().getProgress();

        if (Float.compare(fp, 1f) == 0) {
            return 100;
        }

        int p = (int) (th.getStatus().getProgress() * 100);

        return Math.min(p, 100);
    }

    @Override
    public void prioritizeFiles(Priority[] priorities)
    {
        if (th == null) {
            return;
        }

        if (priorities != null) {
            /* Priorities for all files, priorities list for some selected files not supported */
            if (th.getTorrentInfo().numFiles() != priorities.length) {
                return;
            }

            th.prioritizeFiles(priorities);

        } else {
            /* Did they just add the entire torrent (therefore not selecting any priorities) */
            final Priority[] wholeTorrentPriorities =
                    Priority.array(Priority.NORMAL, th.getTorrentInfo().numFiles());

            th.prioritizeFiles(wholeTorrentPriorities);
        }
    }

    @Override
    public long getSize()
    {
        TorrentInfo info = th.getTorrentInfo();

        return info != null ? info.totalSize() : 0;
    }

    @Override
    public long getDownloadSpeed()
    {
        return (isFinished() || isPaused() || isSeeding()) ? 0 : th.getStatus().getDownloadPayloadRate();
    }

    @Override
    public long getUploadSpeed()
    {
        return ((isFinished() && !isSeeding()) || isPaused()) ? 0 : th.getStatus().getUploadPayloadRate();
    }

    @Override
    public void remove(boolean withFiles)
    {
        Session session = engine.getSession();

        incompleteFilesToRemove = getIncompleteFiles();

        if (th.isValid()) {
            if (withFiles) {
                session.removeTorrent(th, Session.Options.DELETE_FILES);
            } else {
                session.removeTorrent(th);
            }
        }
    }

    /*
     * Deletes incomplete files.
     */

    private void finalCleanup(Set<File> incompleteFiles)
    {
        if (incompleteFiles != null) {
            for (File f : incompleteFiles) {
                try {
                    if (f.exists() && !f.delete()) {
                        Log.w(TAG, "Can't delete file " + f);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Can't delete file " + f + ", ex: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public Set<File> getIncompleteFiles()
    {
        Set<File> s = new HashSet<>();

        try {
            if (!th.isValid()) {
                return s;
            }

            long[] progress = th.getFileProgress(TorrentHandle.FileProgressFlags.PIECE_GRANULARITY);

            TorrentInfo ti = th.getTorrentInfo();
            FileStorage fs = ti.files();

            String prefix = torrent.getDownloadPath();

            File torrentFile = new File(torrent.getTorrentFilePath());

            if (!torrentFile.exists()) {
                return s;
            }

            long createdTime = torrentFile.lastModified();

            for (int i = 0; i < progress.length; i++) {
                String filePath = fs.filePath(i);
                long fileSize = fs.fileSize(i);

                if (progress[i] < fileSize) {
                    /* Lets see if indeed the file is incomplete */
                    File f = new File(prefix, filePath);

                    if (!f.exists()) {
                        /* Nothing to do here */
                        continue;
                    }

                    if (f.lastModified() >= createdTime) {
                        /* We have a file modified (supposedly) by this transfer */
                        s.add(f);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calculating the incomplete files set of " + torrent.getId());
        }

        return s;
    }

    @Override
    public int getActiveTime()
    {
        return th.getStatus().getActiveTime();
    }

    @Override
    public int getSeedingTime()
    {
        return th.getStatus().getSeedingTime();
    }

    @Override
    public long getReceivedBytes()
    {
        return th.getStatus().totalPayloadDownload();
    }

    @Override
    public long getTotalReceivedBytes()
    {
        return th.getStatus().getAllTimeDownload();
    }

    @Override
    public long getSentBytes()
    {
        return th.getStatus().totalPayloadUpload();
    }

    @Override
    public long getTotalSentBytes()
    {
        return th.getStatus().getAllTimeUpload();
    }

    @Override
    public int getConnectedPeers()
    {
        return th.getStatus().getNumPeers();
    }

    @Override
    public int getConnectedSeeds()
    {
        return th.getStatus().getNumSeeds();
    }

    @Override
    public int getTotalPeers()
    {
        return th.getStatus().getListPeers();
    }

    @Override
    public int getTotalSeeds()
    {
        return th.getStatus().getListSeeds();
    }

    @Override
    public void requestTrackerAnnounce()
    {
        th.forceReannounce();
    }

    @Override
    public void requestTrackerScrape()
    {
        th.scrapeTracker();
    }

    @Override
    public Set<String> getTrackersUrl()
    {
        List<AnnounceEntry> trackers = th.trackers();
        Set<String> urls = new HashSet<>(trackers.size());

        for (AnnounceEntry entry : trackers) {
            urls.add(entry.url());
        }

        return urls;
    }

    @Override
    public List<AnnounceEntry> getTrackers()
    {
        return th.trackers();
    }

    @Override
    public ArrayList<PeerInfo> getPeers()
    {
        return th.peerInfo();
    }

    @Override
    public TorrentStatus getTorrentStatus()
    {
        return th.getStatus();
    }

    @Override
    public long getTotalWanted()
    {
        return th.getStatus().getTotalWanted();
    }

    @Override
    public void replaceTrackers(Set<String> trackers)
    {
        List<AnnounceEntry> urls = new ArrayList<>(trackers.size());

        for (String url : trackers) {
            urls.add(new AnnounceEntry(url));
        }

        th.replaceTrackers(urls);
        th.saveResumeData();
    }

    @Override
    public void addTrackers(Set<String> trackers)
    {
        for (String url : trackers) {
            th.addTracker(new AnnounceEntry(url));
        }

        th.saveResumeData();
    }

    @Override
    public boolean[] pieces()
    {
        bitfield bitfield = th.getStatus().pieces().swig();
        boolean[] pieces = new boolean[bitfield.size()];

        for (int i =0; i < bitfield.size(); i++) {
            pieces[i] = bitfield.get_bit(i);
        }

        return pieces;
    }

    @Override
    public String makeMagnet()
    {
        return th.makeMagnetUri();
    }

    @Override
    public void setSequentialDownload(boolean sequential)
    {
        th.setSequentialDownload(sequential);
    }

    @Override
    public long getETA() {
        if (getStateCode() != TorrentStateCode.DOWNLOADING) {
            return 0;
        }

        TorrentInfo ti = th.getTorrentInfo();
        if (ti == null) {
            return 0;
        }

        TorrentStatus status = th.getStatus();
        long left = ti.totalSize() - status.getTotalDone();
        long rate = status.getDownloadPayloadRate();

        if (left <= 0) {
            return 0;
        }

        if (rate <= 0) {
            return -1;
        }

        return left / rate;
    }

    @Override
    public TorrentInfo getTorrentInfo()
    {
        return th.getTorrentInfo();
    }

    @Override
    public void setDownloadPath(String path)
    {
        final int ALWAYS_REPLACE_FILES = 0;

        try {
            th.moveStorage(path, ALWAYS_REPLACE_FILES);

        } catch (Exception e) {
            Log.e(TAG, "Error changing save path: ");
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    @Override
    public long[] getFilesReceivedBytes()
    {
        if (!th.isValid()) {
            return null;
        }

        return th.getFileProgress(TorrentHandle.FileProgressFlags.PIECE_GRANULARITY);
    }

    @Override
    public void forceRecheck()
    {
        th.forceRecheck();
    }

    @Override
    public int getNumDownloadedPieces()
    {
        return th.getStatus().getNumPieces();
    }

    @Override
    public double getShareRatio()
    {
        long uploaded = getTotalSentBytes();

        long allTimeReceived = getTotalReceivedBytes();
        long totalDone = th.getStatus().getTotalDone();

        /*
         * Special case for a seeder who lost its stats,
         * also assume nobody will import a 99% done torrent
         */
        long downloaded = (allTimeReceived < totalDone * 0.01 ? totalDone : allTimeReceived);

        if (downloaded == 0) {
            return (uploaded == 0 ? 0.0 : MAX_RATIO);
        }

        double ratio = (double) uploaded / (double) downloaded;

        return (ratio > MAX_RATIO ? MAX_RATIO : ratio);
    }

    @Override
    public File getPartsFile()
    {
        return parts;
    }

    @Override
    public void setDownloadSpeedLimit(int limit)
    {
        th.setDownloadLimit(limit);
        th.saveResumeData();
    }

    @Override
    public int getDownloadSpeedLimit()
    {
        return th.getDownloadLimit();
    }

    @Override
    public void setUploadSpeedLimit(int limit)
    {
        th.setUploadLimit(limit);
        th.saveResumeData();
    }

    @Override
    public int getUploadSpeedLimit()
    {
        return th.getUploadLimit();
    }

    @Override
    public TorrentStateCode getStateCode()
    {
        if (!engine.isStarted()) {
            return TorrentStateCode.STOPPED;
        }

        if (isPaused()) {
            return TorrentStateCode.PAUSED;
        }

        if (!th.isValid()) {
            return TorrentStateCode.ERROR;
        }

        TorrentStatus status = th.getStatus();

        if (status.isPaused() && status.isFinished()) {
            return TorrentStateCode.FINISHED;
        }

        if (status.isPaused() && !status.isFinished()) {
            return TorrentStateCode.PAUSED;
        }

        if (!status.isPaused() && status.isFinished()) {
            return TorrentStateCode.SEEDING;
        }

        TorrentStatus.State stateCode = status.getState();

        switch (stateCode) {
            case QUEUED_FOR_CHECKING:
                return TorrentStateCode.QUEUED_FOR_CHECKING;
            case CHECKING_FILES:
                return TorrentStateCode.CHECKING;
            case DOWNLOADING_METADATA:
                return TorrentStateCode.DOWNLOADING_METADATA;
            case DOWNLOADING:
                return TorrentStateCode.DOWNLOADING;
            case FINISHED:
                return TorrentStateCode.FINISHED;
            case SEEDING:
                return TorrentStateCode.SEEDING;
            case ALLOCATING:
                return TorrentStateCode.ALLOCATING;
            case CHECKING_RESUME_DATA:
                return TorrentStateCode.CHECKING;
            case UNKNOWN:
                return TorrentStateCode.UNKNOWN;
            default:
                return TorrentStateCode.UNKNOWN;
        }
    }

    @Override
    public boolean isPaused()
    {
        return th.getStatus(true).isPaused() || engine.isPaused() || !engine.isStarted();
    }

    @Override
    public boolean isSeeding()
    {
        return th.getStatus().isSeeding();
    }

    @Override
    public boolean isFinished()
    {
        return th.getStatus().isFinished();
    }

    @Override
    public boolean isDownloading()
    {
        return getDownloadSpeed() > 0;
    }

    @Override
    public boolean isSequentialDownload()
    {
        return th.getStatus().isSequentialDownload();
    }
}
