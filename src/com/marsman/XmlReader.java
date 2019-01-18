package com.marsman;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class XmlReader {

    public enum EVENT_TYPE {
        START,
        END,
        TEXT
    }

    private Consumer<XmlReaderStatus> _startHandler;
    private Consumer<XmlReaderStatus> _endHandler;
    private Consumer<XmlReaderStatus> _textHandler;

    private String _filename;
    private long _startPos;

    public static byte[] stringToBytes(String val) {
        return val.getBytes(StandardCharsets.UTF_8);
    }

    public static String bytesToString(byte[] val) {
        if (val == null) return null;
        return new String(val, StandardCharsets.UTF_8);
    }

    public class XmlReaderStatus {
        private boolean _tagSymbolSmall;    // <
        private boolean _tagSymbolBig;      // >
        private boolean _tagSymbolSlash;    // /
        private boolean _tagSymbolQuote;    // "

        private boolean _outerBlocked;      // 在标签之外

        private byte[] _curStr = new byte[64 * 1024];
        private int _curStrCount = 0;
        private byte[] _attrNameStr = new byte[64];
        private int _curAttrNameCount = 0;

        private byte[] _tagName = new byte[64];
        private int _tagNameCount = 0;

        private byte[][][] _attrs = new byte[64][][];
        private int[][] _attrsPerCount = new int[64][2];
        private int _attrsCount = 0;

        private boolean _isSingleTag = true;        // 是单独的标签

        private long _blockStartPos = 0;
        private long _blockEndPos = 0;

        public XmlReaderStatus() {
            for(int i = 0; i < this._attrs.length; i++) {
                this._attrs[i] = new byte[2][64 * 1024];
            }
        }

        private void reset() {
            this._tagSymbolBig = false;
            this._tagSymbolQuote = false;
            this._tagSymbolSlash = false;
            this._tagSymbolSmall = false;
            this._outerBlocked = false;
            this._curStrCount = 0;
            this._curAttrNameCount = 0;
            this._tagNameCount = 0;
            this._attrsCount = 0;
            this._isSingleTag = true;
        }

        public boolean isSingleTag() {
            return this._isSingleTag;
        }

        public byte[] getText() {
            return Arrays.copyOf(this._curStr, this._curStrCount);
        }

        public long getBlockStartPos() {
            return this._blockStartPos;
        }

        public long getBlockEndPos() {
            return this._blockEndPos;
        }

        public byte[] getAttr(byte[] attrNameIntArr) {
            int i = 0;
            byte[] found = null;
            for(byte[][] _attr : _attrs) {
                if (i >= this._attrsCount) break;
                int count = this._attrsPerCount[i][0];
                int valCount = this._attrsPerCount[i][1];
                i++;
                if (count != attrNameIntArr.length) {
                    continue;
                }
                boolean diff = false;
                for (int j = 0; j < count; j++) {
                    if (_attr[0][j] != attrNameIntArr[j]) {
                        diff = true;
                        break;
                    }
                }
                if (!diff) {
                    found = Arrays.copyOf(_attr[1], valCount);
                    break;
                }
            }
            return found;
        }

        public byte[] getTagName() {
            return Arrays.copyOf(this._tagName, this._tagNameCount);
        }

        public boolean testTag(byte[] tagNameArr) {
            if (this._tagNameCount != tagNameArr.length) {
                return false;
            }
            for (int j = 0; j < this._tagNameCount; j++) {
                if (tagNameArr[j] != this._tagName[j]) {
                    return false;
                }
            }
            return true;
        }

    }

    private class FileLoadBuffer {
        public long posStart;
        public long posEnd;
        public ByteBuffer buffer;

        public FileLoadBuffer(int bufferLength) {
            buffer = ByteBuffer.allocateDirect(bufferLength);
        }
    }

    private XmlReaderStatus _status = new XmlReaderStatus();

    private Thread _fileLoadThread;
    private volatile boolean _fileLoadExitSignal;
    private volatile boolean _readFinishedSignal;
    private FileChannel _reader;

    public long _handledPos;

    private ConcurrentLinkedQueue<FileLoadBuffer> _loaded = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<FileLoadBuffer> _rest = new ConcurrentLinkedQueue<>();

    private void loadFile(long startPos) {
        long curPos = startPos;
        try {
            while (true) {
                if (this._fileLoadExitSignal) {
                    break;
                }
                if (_rest.isEmpty()) {
                    Thread.sleep(10);
                } else {
                    FileLoadBuffer buf = _rest.poll();
                    buf.buffer.position(0);
                    int count = this._reader.read(buf.buffer, curPos);
                    if (count == 0 || count == -1) break;
                    buf.posStart = curPos;
                    buf.posEnd = curPos + count - 1;
                    _loaded.add(buf);
                    curPos += count;
                }
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        } finally {
            this._readFinishedSignal = true;
            try {
                this._reader.close();
            } catch (Exception e) {
                System.err.println(e.getMessage());
            }

        }
    }


    public XmlReader(String filename) throws FileNotFoundException {
        this(filename, 0);
    }

    public XmlReader(String filename, long startPos) throws FileNotFoundException {
        this(filename, startPos, 1024*1024, 10);
    }

    public XmlReader(String filename, long startPos, int bufferLength, int bufferCount) throws FileNotFoundException {
        for(int i = 0; i < bufferCount; i++) {
            this._rest.add(new FileLoadBuffer(bufferLength));
        }
        this._filename = filename;
        this._startPos = startPos;
        this._reader = (new RandomAccessFile(this._filename, "r")).getChannel();
    }

    public void listen(EVENT_TYPE type, Consumer<XmlReaderStatus> consumer) {
        switch (type) {
            case START: this._startHandler = consumer; break;
            case END: this._endHandler = consumer; break;
            case TEXT: this._textHandler = consumer; break;
        }
    }

    private boolean addChar(byte b) {
        if (this._status._tagSymbolQuote || this._status._outerBlocked) {
            this._status._curStr[this._status._curStrCount++] = b;
            return true;
        }
        return false;
    }

    private void handleChar(byte b, long pos) {
        this._handledPos = pos;
        switch (b) {
            case 60:  // '<'
                if (_status._outerBlocked || !addChar(b)) {
                    if (this._textHandler != null && this._status._curStrCount != 0) {
                        this._textHandler.accept(this._status);
                    }
                    _status.reset();
                    _status._blockStartPos = pos;
                }
                break;
            case 47:    // /
                if (!addChar(b)) {
                    if (pos - _status._blockStartPos == 1) {
                        _status._isSingleTag = false;
                    }
                    _status._tagSymbolSlash = true;
                }
                break;
            case 32:  // ' '
            case 9:     // /t
            case 12:
            case 13:    // 换行
                if (!this.addChar(b)) {
                    if (_status._curAttrNameCount != 0) {
                        byte[] bytesName =  _status._attrs[this._status._attrsCount][0];
                        byte[] bytesVal = _status._attrs[this._status._attrsCount][1];
                        System.arraycopy(_status._attrNameStr, 0, bytesName, 0, this._status._curAttrNameCount);
                        if (_status._curStrCount >= 0) System.arraycopy(this._status._curStr, 0, bytesVal, 0, this._status._curStrCount);
                        _status._attrsPerCount[this._status._attrsCount][1] = this._status._curStrCount;
                        _status._attrsPerCount[this._status._attrsCount][0] = this._status._curAttrNameCount;
                        _status._curStrCount = 0;
                        _status._curAttrNameCount = 0;
                        _status._attrsCount++;
                    } else if (_status._tagNameCount == 0) {
                        System.arraycopy(this._status._curStr, 0, this._status._tagName, 0, this._status._curStrCount);
                        _status._tagNameCount = this._status._curStrCount;
                        _status._curStrCount = 0;
                    } else {
                        byte[] bytesName = _status._attrs[this._status._attrsCount][0];
                        System.arraycopy(this._status._attrNameStr, 0, bytesName, 0, this._status._curAttrNameCount);
                        _status._attrsPerCount[_status._attrsCount][1] = 0;
                        _status._attrsPerCount[_status._attrsCount][0] = this._status._curAttrNameCount;
                        _status._curStrCount = 0;
                        _status._curAttrNameCount = 0;
                        _status._attrsCount++;
                    }
                }
                break;
            case 62: // >
                if (!addChar(b)) {
                    if (_status._tagNameCount == 0) {
                        System.arraycopy(_status._curStr, 0, _status._tagName, 0, _status._curStrCount);
                        _status._tagNameCount = _status._curStrCount;
                        _status._curStrCount = 0;
                    }
                    this._status._blockEndPos = pos;
                    if (_status._isSingleTag) {
                        if (_startHandler != null) _startHandler.accept(_status);
                    } else if (_status._tagSymbolSlash) {
                        if (_endHandler != null) this._endHandler.accept(_status);
                    } else {
                        if (_startHandler != null) _endHandler.accept(this._status);
                    }
                    _status.reset();
                    _status._curAttrNameCount = 0;
                    _status._outerBlocked = true;
                }
                break;
            case 61:   // =
                if (!this.addChar(b)) {
                    System.arraycopy(this._status._curStr, 0, this._status._attrNameStr, 0, this._status._curStrCount);
                    _status._curAttrNameCount = this._status._curStrCount;
                    _status._curStrCount = 0;
                }
                break;
            case 34:   //
                _status._tagSymbolQuote = !this._status._tagSymbolQuote;
                break;
            case '?':
            case '!':
            case '-':
            case '[':
            case ']':
                addChar(b);
                break;
            default:
                _status._curStr[this._status._curStrCount++] = b;
        }
    }

    public void read() throws FileNotFoundException, InterruptedException {
        this._fileLoadThread = new Thread(()->{
            this.loadFile(this._startPos);
        });
        this._fileLoadThread.start();
        while(true) {
            if (this._fileLoadExitSignal) break;
            if(this._loaded.isEmpty()) {
                if (this._readFinishedSignal) break;
                Thread.sleep(10);
            } else {
                FileLoadBuffer buf = this._loaded.poll();
                buf.buffer.position(0);
                for(long i = 0; i <= buf.posEnd - buf.posStart; i++) {
                    this.handleChar(buf.buffer.get(), buf.posStart+i);
                }
                this._rest.add(buf);
            }
        }
    }

    public long fileSize() throws IOException {
        return this._reader.size();
    }

    public long getProcessed() {
        return this._handledPos;
    }

    public void stop() throws IOException, InterruptedException {
        this._fileLoadExitSignal = true;
        this._fileLoadThread.join();
        this._reader.close();
    }
}
