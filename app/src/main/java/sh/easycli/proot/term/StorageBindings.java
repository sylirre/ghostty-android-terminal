package sh.easycli.proot.term;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Builds optional PRoot bind mounts for Android shared external storage. */
final class StorageBindings {

    private static final Mount[] STANDARD_MOUNTS = {
            new Mount(Environment.DIRECTORY_DOCUMENTS, "/mnt/documents"),
            new Mount(Environment.DIRECTORY_PICTURES, "/mnt/pictures"),
            new Mount(Environment.DIRECTORY_DCIM, "/mnt/dcim"),
            new Mount(Environment.DIRECTORY_MUSIC, "/mnt/music"),
            new Mount(Environment.DIRECTORY_DOWNLOADS, "/mnt/downloads"),
            new Mount(Environment.DIRECTORY_MOVIES, "/mnt/movies"),
    };

    private StorageBindings() {}

    static List<String> prootArgs(Context ctx) throws IOException {
        File shared = Environment.getExternalStorageDirectory();
        File root = DebianRootfs.dir(ctx);
        List<String> args = new ArrayList<>();
        if (shared.isDirectory()) {
            addBind(root, args, shared, "/mnt/shared");
        }
        for (Mount mount : STANDARD_MOUNTS) {
            File host = Environment.getExternalStoragePublicDirectory(mount.hostType);
            if (host.isDirectory()) addBind(root, args, host, mount.guestPath);
        }
        return args;
    }

    private static void addBind(File root, List<String> args, File host, String guestPath)
            throws IOException {
        ensureGuestDirectory(root, guestPath);
        args.add("-b");
        args.add(host.getAbsolutePath() + ":" + guestPath);
    }

    private static void ensureGuestDirectory(File root, String guestPath) throws IOException {
        String relative = guestPath.startsWith("/") ? guestPath.substring(1) : guestPath;
        File dir = new File(root, relative);
        if (dir.isDirectory()) return;
        if (dir.exists()) throw new IOException("storage mount point is not a directory: "
                + guestPath);
        if (!dir.mkdirs()) throw new IOException("cannot create storage mount point: "
                + guestPath);
    }

    private static final class Mount {
        final String hostType;
        final String guestPath;

        Mount(String hostType, String guestPath) {
            this.hostType = hostType;
            this.guestPath = guestPath;
        }
    }
}
