package com.cedarsoftware.util.io

import groovy.transform.CompileStatic

/**
 * This class adds significant performance increase over using the JDK
 * PushbackReader.  This is due to this class not using synchronization
 * as it is not needed.
 */
@CompileStatic
class FastPushbackReader extends FilterReader
{
    private static final int SNIPPET_LENGTH = 200
    private final int[] buf
    private final int[] snippet
    private int idx
    int line
    int col
    private int snippetLoc = 0

    FastPushbackReader(Reader reader, int size)
    {
        super(reader)
        if (size <= 0)
        {
            throw new JsonIoException("size must be greater than 0")
        }
        buf = new int[size]
        idx = size
        snippet = new int[SNIPPET_LENGTH]
        line = 1
        col = 0
    }

    FastPushbackReader(Reader r)
    {
        this(r, 1)
    }

    String getLastSnippet()
    {
        StringBuilder s = new StringBuilder()
        for (int i=snippetLoc + 1; i < SNIPPET_LENGTH; i++)
        {
            if (appendChar(s, i))
            {
                break
            }
        }
        for (int i=0; i <= snippetLoc; i++)
        {
            if (appendChar(s, i))
            {
                break
            }
        }
        return s.toString();
    }

    private boolean appendChar(StringBuilder s, int i)
    {
        try
        {
            if (snippet[i] == 0)
            {
                return true
            }
            s.appendCodePoint(snippet[i])
        }
        catch (Exception e)
        {
            return true
        }
        return false
    }

    int read()
    {
        final int ch = idx < buf.length ? buf[idx++] : super.read()
        if (ch == 0x0a)
        {
            line++
            col = 0
        }
        else
        {
            col++
        }
        snippet[snippetLoc++] = ch
        if (snippetLoc >= SNIPPET_LENGTH)
        {
            snippetLoc = 0
        }
        return ch
    }

    void unread(int c)
    {
        if (idx == 0)
        {
            throw new JsonIoException("unread(int c) called more than buffer size (" + buf.length + ").  Increase FastPushbackReader's buffer size.  Currently " + buf.length);
        }
        if (c == 0x0a)
        {
            line--
        }
        else
        {
            col--
        }
        buf[--idx] = c
        snippetLoc--
        if (snippetLoc < 0)
        {
            snippetLoc = SNIPPET_LENGTH - 1
        }
        snippet[snippetLoc] = c
    }
}
