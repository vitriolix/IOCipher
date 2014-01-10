/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package info.guardianproject.iocipher;

import info.guardianproject.libcore.io.ErrnoException;
import info.guardianproject.libcore.io.IoUtils;
import info.guardianproject.libcore.io.Libcore;
import info.guardianproject.libcore.io.StructStat;
import info.guardianproject.libcore.io.StructStatFs;
import static info.guardianproject.libcore.io.OsConstants.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import android.os.AsyncTask;
import android.util.Log;

/**
 * An "abstract" representation of a file system entity identified by a
 * pathname. The pathname may be absolute (relative to the root directory
 * of the file system) or relative to the current directory in which the program
 * is running.
 *
 * <p>The actual file referenced by a {@code File} may or may not exist. It may
 * also, despite the name {@code File}, be a directory or other non-regular
 * file.
 *
 * <p>This class provides limited functionality for getting/setting file
 * permissions, file type, and last modified time.
 *
 * <p>On Android strings are converted to UTF-8 byte sequences when sending filenames to
 * the operating system, and byte sequences returned by the operating system (from the
 * various {@code list} methods) are converted to strings by decoding them as UTF-8
 * byte sequences.
 *
 * @see java.io.Serializable
 * @see java.lang.Comparable
 */
public class StreamFile extends File {

    private static final long serialVersionUID = 301077366599181567L;

    /**
     * Reusing a Random makes temporary filenames slightly harder to predict.
     * (Random is thread-safe.)
     */
    private static final Random tempFileRandom = new Random();

    /**
     * The path we return from getPath. This is almost the path we were
     * given, but without duplicate adjacent slashes and without trailing
     * slashes (except for the special case of the root directory). This
     * path may be the empty string.
     *
     * This can't be final because we override readObject.
     */
    private String virtualPath;

    private String realPath;

    /**
     * Constructs a new file using the specified directory and name.
     *
     * @param dir
     *            the directory where the file is stored.
     * @param name
     *            the file's name.
     * @throws NullPointerException
     *             if {@code name} is {@code null}.
     */
    public StreamFile(java.io.File dir, String name) {
        this(dir == null ? null : dir.getPath(), name);
    	setupFifoAndThreads();
    }

    /**
     * Constructs a new file using the specified path.
     *
     * @param path
     *            the path to be used for the file.
     */
    public StreamFile(String path) {
        super(path);
        this.virtualPath = super.getPath();
        setupFifoAndThreads();
    }

    /**
     * Constructs a new File using the specified directory path and file name,
     * placing a path separator between the two.
     *
     * @param dirPath
     *            the path to the directory where the file is stored.
     * @param name
     *            the file's name.
     * @throws NullPointerException
     *             if {@code name == null}.
     */
    public StreamFile(String dirPath, String name) {
        super(dirPath, name);
        this.virtualPath = super.getPath();
        setupFifoAndThreads();
    }

    /**
     * Constructs a new File using the path of the specified URI. {@code uri}
     * needs to be an absolute and hierarchical Unified Resource Identifier with
     * file scheme and non-empty path component, but with undefined authority,
     * query or fragment components.
     *
     * @param uri
     *            the Unified Resource Identifier that is used to construct this
     *            file.
     * @throws IllegalArgumentException
     *             if {@code uri} does not comply with the conditions above.
     * @see #toURI
     * @see java.net.URI
     */
    public StreamFile(URI uri) {
        super(uri);
        this.virtualPath = super.getPath();
        setupFifoAndThreads();
    }

    // Removes duplicate adjacent slashes and any trailing slash.
    private static String fixSlashes(String origPath) {
        // Remove duplicate adjacent slashes.
        boolean lastWasSlash = false;
        char[] newPath = origPath.toCharArray();
        int length = newPath.length;
        int newLength = 0;
        for (int i = 0; i < length; ++i) {
            char ch = newPath[i];
            if (ch == '/') {
                if (!lastWasSlash) {
                    newPath[newLength++] = separatorChar;
                    lastWasSlash = true;
                }
            } else {
                newPath[newLength++] = ch;
                lastWasSlash = false;
            }
        }
        // Remove any trailing slash (unless this is the root of the file system).
        if (lastWasSlash && newLength > 1) {
            newLength--;
        }
        // Reuse the original string if possible.
        return (newLength != length) ? new String(newPath, 0, newLength) : origPath;
    }

    // Joins two path components, adding a separator only if necessary.
    private static String join(String prefix, String suffix) {
        int prefixLength = prefix.length();
        boolean haveSlash = (prefixLength > 0 && prefix.charAt(prefixLength - 1) == separatorChar);
        if (!haveSlash) {
            haveSlash = (suffix.length() > 0 && suffix.charAt(0) == separatorChar);
        }
        return haveSlash ? (prefix + suffix) : (prefix + separatorChar + suffix);
    }

    private static void checkURI(URI uri) {
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("URI is not absolute: " + uri);
        } else if (!uri.getRawSchemeSpecificPart().startsWith("/")) {
            throw new IllegalArgumentException("URI is not hierarchical: " + uri);
        }
        if (!"file".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Expected file scheme in URI: " + uri);
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.length() == 0) {
            throw new IllegalArgumentException("Expected non-empty path in URI: " + uri);
        }
        if (uri.getRawAuthority() != null) {
            throw new IllegalArgumentException("Found authority in URI: " + uri);
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("Found query in URI: " + uri);
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Found fragment in URI: " + uri);
        }
    }

    /**
     * Tests whether or not this process is allowed to execute this file.
     * Note that this is a best-effort result; the only way to be certain is
     * to actually attempt the operation.
     *
     * @return {@code true} if this file can be executed, {@code false} otherwise.
     * @since 1.6
     */
    public boolean canExecute() {
        return doAccess(X_OK);
    }

    /**
     * Indicates whether the current context is allowed to read from this file.
     *
     * @return {@code true} if this file can be read, {@code false} otherwise.
     */
    public boolean canRead() {
        return doAccess(R_OK);
    }

    /**
     * Indicates whether the current context is allowed to write to this file.
     *
     * @return {@code true} if this file can be written, {@code false}
     *         otherwise.
     */
    public boolean canWrite() {
        return doAccess(W_OK);
    }

    private boolean doAccess(int mode) {
        try {
            return Libcore.os.access(virtualPath, mode);
        } catch (ErrnoException errnoException) {
            return false;
        }
    }

    /**
     * Returns the relative sort ordering of the paths for this file and the
     * file {@code another}. The ordering is platform dependent.
     *
     * @param another
     *            a file to compare this file to
     * @return an int determined by comparing the two paths. Possible values are
     *         described in the Comparable interface.
     * @see Comparable
     */
    public int compareTo(StreamFile another) {
        return this.getPath().compareTo(another.getPath());
    }

    /**
     * Deletes this file. Directories must be empty before they will be deleted.
     *
     * <p>Note that this method does <i>not</i> throw {@code IOException} on failure.
     * Callers must check the return value.
     *
     * @return {@code true} if this file was deleted, {@code false} otherwise.
     */
    public boolean delete() {
    	// TODO should tear down read/write thread
        try {
            Libcore.os.remove(virtualPath);
            return true;
        } catch (ErrnoException errnoException) {
            return false;
        }
    }

    /**
     * Returns the absolute path of this file. An absolute path is a path that starts at a root
     * of the file system. On Android, there is only one root: {@code /}.
     *
     * <p>A common use for absolute paths is when passing paths to a {@code Process} as
     * command-line arguments, to remove the requirement implied by relative paths, that the
     * child must have the same working directory as its parent.
     */
    public String getAbsolutePath() {
    	return realPath;
//        if (isAbsolute()) {
//            return virtualPath;
//        }
//        String userDir = System.getProperty("user.dir");
//        return virtualPath.length() == 0 ? userDir : join(userDir, virtualPath);
    }
    
    public String getVirtualPath() {
    	return virtualPath;
    }

    /**
     * Returns a new file constructed using the absolute path of this file.
     * Equivalent to {@code new File(this.getAbsolutePath())}.
     */
    public StreamFile getAbsoluteFile() {
        return new StreamFile(virtualPath);
    }

    /**
     * Returns a new IOCipher File constructed using the virtual path of this file.
     * Equivalent to {@code new File(this.getVirtualPath())}.
     */
    public File getIoCipherFile() {
        return new File(this.getVirtualPath());
    }

    /**
     * Returns a new file created using the canonical path of this file.
     * Equivalent to {@code new File(this.getCanonicalPath())}.
     *
     * @return the new file constructed from this file's canonical path.
     * @throws IOException
     *             if an I/O error occurs.
     */
    public StreamFile getCanonicalFile() throws IOException {
        return new StreamFile(getCanonicalPath());
    }

    /**
     * Returns the pathname of the parent of this file. This is the path up to
     * but not including the last name. {@code null} is returned if there is no
     * parent.
     *
     * @return this file's parent pathname or {@code null}.
     */
    public String getParent() {
        int length = virtualPath.length(), firstInPath = 0;
        if (separatorChar == '\\' && length > 2 && virtualPath.charAt(1) == ':') {
            firstInPath = 2;
        }
        int index = virtualPath.lastIndexOf(separatorChar);
        if (index == -1 && firstInPath > 0) {
            index = 2;
        }
        if (index == -1 || virtualPath.charAt(length - 1) == separatorChar) {
            return null;
        }
        if (virtualPath.indexOf(separatorChar) == index
                && virtualPath.charAt(firstInPath) == separatorChar) {
            return virtualPath.substring(0, index + 1);
        }
        return virtualPath.substring(0, index);
    }

    /**
     * Returns a new file made from the pathname of the parent of this file.
     * This is the path up to but not including the last name. {@code null} is
     * returned when there is no parent.
     *
     * @return a new file representing this file's parent or {@code null}.
     */
    public StreamFile getParentFile() {
        String tempParent = getParent();
        if (tempParent == null) {
            return null;
        }
        return new StreamFile(tempParent);
    }

    /**
     * Returns the path of this file.
     *
     * @return this file's path.
     */
    public String getPath() {
        return virtualPath;
    }

    /**
     * Equivalent to setReadable(readable, true).
     * @see #setReadable(boolean, boolean)
     * @since 1.6
     */
    public boolean setReadable(boolean readable) {
        return setReadable(readable, true);
    }

    /**
     * Equivalent to setWritable(writable, true).
     * @see #setWritable(boolean, boolean)
     * @since 1.6
     */
    public boolean setWritable(boolean writable) {
        return setWritable(writable, true);
    }

    /**
     * Returns an array of files contained in the directory represented by this
     * file. The result is {@code null} if this file is not a directory. The
     * paths of the files in the array are absolute if the path of this file is
     * absolute, they are relative otherwise.
     *
     * @return an array of files or {@code null}.
     */
    public StreamFile[] listFiles() {
        return filenamesToFiles(list());
    }

    /**
     * Gets a list of the files in the directory represented by this file. This
     * list is then filtered through a FilenameFilter and files with matching
     * names are returned as an array of files. Returns {@code null} if this
     * file is not a directory. If {@code filter} is {@code null} then all
     * filenames match.
     * <p>
     * The entries {@code .} and {@code ..} representing the current and parent
     * directories are not returned as part of the list.
     *
     * @param filter
     *            the filter to match names against, may be {@code null}.
     * @return an array of files or {@code null}.
     */
    public StreamFile[] listFiles(FilenameFilter filter) {
        return filenamesToFiles(list(filter));
    }

    /**
     * Gets a list of the files in the directory represented by this file. This
     * list is then filtered through a FileFilter and matching files are
     * returned as an array of files. Returns {@code null} if this file is not a
     * directory. If {@code filter} is {@code null} then all files match.
     * <p>
     * The entries {@code .} and {@code ..} representing the current and parent
     * directories are not returned as part of the list.
     *
     * @param filter
     *            the filter to match names against, may be {@code null}.
     * @return an array of files or {@code null}.
     */
    public StreamFile[] listFiles(FileFilter filter) {
        StreamFile[] files = listFiles();
        if (filter == null || files == null) {
            return files;
        }
        List<StreamFile> result = new ArrayList<StreamFile>(files.length);
        for (StreamFile file : files) {
            if (filter.accept(file)) {
                result.add(file);
            }
        }
        return result.toArray(new StreamFile[result.size()]);
    }

    /**
     * Converts a String[] containing filenames to a File[].
     * Note that the filenames must not contain slashes.
     * This method is to remove duplication in the implementation
     * of File.list's overloads.
     */
    private StreamFile[] filenamesToFiles(String[] filenames) {
        if (filenames == null) {
            return null;
        }
        int count = filenames.length;
        StreamFile[] result = new StreamFile[count];
        for (int i = 0; i < count; ++i) {
            result[i] = new StreamFile(this, filenames[i]);
        }
        return result;
    }

    /**
     * Creates an empty temporary file using the given prefix and suffix as part
     * of the file name. If {@code suffix} is null, {@code .tmp} is used. This
     * method is a convenience method that calls
     * {@link #createTempFile(String, String, StreamFile)} with the third argument
     * being {@code null}.
     *
     * @param prefix
     *            the prefix to the temp file name.
     * @param suffix
     *            the suffix to the temp file name.
     * @return the temporary file.
     * @throws IOException
     *             if an error occurs when writing the file.
     */
    public static StreamFile createTempFile(String prefix, String suffix) throws IOException {
        return createTempFile(prefix, suffix, null);
    }

    /**
     * Creates an empty temporary file in the given directory using the given
     * prefix and suffix as part of the file name. If {@code suffix} is null, {@code .tmp} is used.
     *
     * <p>Note that this method does <i>not</i> call {@link #deleteOnExit}, but see the
     * documentation for that method before you call it manually.
     *
     * @param prefix
     *            the prefix to the temp file name.
     * @param suffix
     *            the suffix to the temp file name.
     * @param directory
     *            the location to which the temp file is to be written, or
     *            {@code null} for the default location for temporary files,
     *            which is taken from the "java.io.tmpdir" system property. It
     *            may be necessary to set this property to an existing, writable
     *            directory for this method to work properly.
     * @return the temporary file.
     * @throws IllegalArgumentException
     *             if the length of {@code prefix} is less than 3.
     * @throws IOException
     *             if an error occurs when writing the file.
     */
    public static StreamFile createTempFile(String prefix, String suffix, StreamFile directory)
            throws IOException {
        // Force a prefix null check first
        if (prefix.length() < 3) {
            throw new IllegalArgumentException("prefix must be at least 3 characters");
        }
        if (suffix == null) {
            suffix = ".tmp";
        }
        StreamFile tmpDirFile = directory;
        if (tmpDirFile == null) {
            String tmpDir = System.getProperty("java.io.tmpdir", ".");
            tmpDirFile = new StreamFile(tmpDir);
        }
        StreamFile result;
        do {
            result = new StreamFile(tmpDirFile, prefix + tempFileRandom.nextInt() + suffix);
        } while (!result.createNewFile());
        return result;
    }
    
    /////////////////////////////////////////////////////////// securecam stuff
    private void setupFifoAndThreads() {
    	// TODO create fifo's with unique names
    	Pipes.createfifonative();
    	realPath = PIPE_FILE_1; // FIXME this needs to be done more smartly
    }
//    private static final String PIPE_FILE_1 = "/data/data/info.guardianproject.securecamtest/pipe0";
    private static final String PIPE_FILE_1 = "/storage/emulated/legacy/DCIM/foo.jpg"; // this is weird because we are playing with paths, this is actually a fifo for real
    private TransferThread transferThread;
    
    public void _startReadFromVFS() {
    	InputStream in = null; // vfs file
    	OutputStream out = null; // pipe file
		try {
			in = new FileInputStream(virtualPath); 
			out = new java.io.FileOutputStream(new java.io.File(realPath)); 
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		// TODO what to do if a thread already exists?  probably don't allow a new one to get started
		if ((in != null) && (out !=null)) {
			transferThread = new TransferThread(in, out);
			transferThread.startWrapped();
		}
    }
    
    public void startReadFromVFS() {
    	// TODO how to start up the threads and not block?  it needs to be also wrapped in an asynctask
		(new AsyncTask<String, Long, Integer>(){

			@Override
			protected Integer doInBackground(String... params) {
				_startReadFromVFS(); // this method blocks until the other end of the pipe is active so we need to wrap it to not block the app here
				return null;
			}
			
		}).execute("");
    }
    
    private void _startWriteToVFS() {
    	InputStream in = null; // pipe file
    	OutputStream out = null; // vfs file
		try {
			in = new java.io.FileInputStream(new java.io.File(PIPE_FILE_1)); 
			out = new FileOutputStream(this); 
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		// TODO what to do if a thread already exists?  probably don't allow a new one to get started
		if ((in != null) && (out !=null)) {
			transferThread = new TransferThread(in, out);
			transferThread.start();
		}
    }
    
    public void startWriteToVFS() {
    	// TODO how to start up the threads and not block?  it needs to be also wrapped in an asynctask
		(new AsyncTask<String, Long, Integer>(){

			@Override
			protected Integer doInBackground(String... params) {
				_startWriteToVFS(); // this method blocks until the other end of the pipe is active so we need to wrap it to not block the app here
				return null;
			}
			
		}).execute("");
    }
    
    // TODO do we need a way to restart the thread from the beggining? or should they just kill the object and recreate?
    
    public void close() {
    	// TODO clean up fifo's
    	// TODO clean up threads
    }
    
    public java.io.File getJavaIoFile() {
    	return new java.io.File(PIPE_FILE_1);
    }
    
	static class TransferThread extends Thread {
		InputStream in;
		OutputStream out;

		TransferThread(InputStream in, OutputStream out) {
			this.in = in;
			this.out = out;
		}
		
		public void startWrapped() {
	    	// TODO how to start up the threads and not block?  it needs to be also wrapped in an asynctask
			(new AsyncTask<String, Long, Integer>(){

				@Override
				protected Integer doInBackground(String... params) {
					TransferThread.this.start(); // this method blocks until the other end of the pipe is active so we need to wrap it to not block the app here
					return null;
				}
				
			}).execute("");
		}

		@Override
		public void run() {
			byte[] buf = new byte[8192];
			int len;

			try {
				while ((len = in.read(buf)) > 0) {
					out.write(buf, 0, len);
					Log.d("foo", "TransferThread: " + String.format("0x%20x", buf[0]));
				}

				in.close();

				out.flush();
				if (out instanceof FileOutputStream) 
					((FileOutputStream)out).getFD().sync();
				if (out instanceof java.io.FileOutputStream) 
					((java.io.FileOutputStream)out).getFD().sync();
				out.close();
			} catch (IOException e) {
				Log.e(getClass().getSimpleName(),
						"Exception transferring file", e);
			}
		}
	}
}
