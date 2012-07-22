/*
 *  Copyright 2012 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.*;
import java.util.*;

import org.junit.*;
import static org.junit.Assert.*;

import static org.cojen.tupl.TestUtils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SnapshotTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SnapshotTest.class.getName());
    }

    @Test
    public void test() throws Exception {
        File base = newTempBaseFile();
        File snapshotBase = newTempBaseFile();
        test(base, snapshotBase);
        deleteTempDatabases();
    }

    private static void test(File base, File snapshotBase) throws Exception {
        File snapshot = new File(snapshotBase.getParentFile(), snapshotBase.getName() + ".db");

        final Database db = Database.open
            (new DatabaseConfig()
             .baseFile(base)
             .minCacheSize(100000000)
             .durabilityMode(DurabilityMode.NO_FLUSH));
        final Index index = db.openIndex("test1");

        for (int i=0; i<10000000; i++) {
            String key = "key-" + i;
            String value = "value-" + i;
            index.store(null, key.getBytes(), value.getBytes());
        }

        final FileOutputStream out = new FileOutputStream(snapshot);

        class Slow extends OutputStream {
            volatile boolean fast;

            public void write(int b) throws IOException {
                throw new IOException();
            }

            public void write(byte[] b, int off, int len) throws IOException {
                if (!fast) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
                out.write(b, off, len);
            }

            public void close() throws IOException {
                out.close();
            }
        };

        final Slow slow = new Slow();

        Thread t = new Thread() {
            public void run() {
                try {
                    Random rnd = new Random(5198473);
                    for (int i=0; i<1000000; i++) {
                        int k = rnd.nextInt(1000000);
                        String key = "key-" + k;
                        String value = "rnd-" + k;
                        index.store(null, key.getBytes(), value.getBytes());
                    }
                    slow.fast = true;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        t.setDaemon(true);
        t.start();

        Snapshot s = db.beginSnapshot();
        long expectedLength = s.length();
        s.writeTo(slow);
        out.close();
        s.close();

        t.join();
        db.close();

        assertEquals(expectedLength, snapshot.length());

        final Database restored = Database.open
            (new DatabaseConfig()
             .baseFile(snapshotBase)
             .minCacheSize(100000000)
             .durabilityMode(DurabilityMode.NO_FLUSH));
        final Index restoredIx = restored.openIndex("test1");

        for (int i=0; i<10000000; i++) {
            byte[] key = ("key-" + i).getBytes();
            byte[] value = restoredIx.load(null, key);
            if (value == null) {
                break;
            }
            byte[] expectedValue = ("value-" + i).getBytes();
            fastAssertArrayEquals(expectedValue, value);
        }

        restored.close();
    }
}
