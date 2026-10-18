package thumbnailer.spi;

/** Container kind of an album: a directory, a zip (JDK), or any other archive 7-Zip reads (RAR, 7z, tar, ISO, tar.gz ...). */
public enum Kind { DIRECTORY, ZIP, ARCHIVE }
