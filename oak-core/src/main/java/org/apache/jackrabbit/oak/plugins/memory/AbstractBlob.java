/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.plugins.memory;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.InputSupplier;

import org.apache.jackrabbit.oak.api.Blob;

/**
 * Abstract base class for {@link Blob} implementations.
 * This base class provides default implementations for
 * {@code hashCode} and {@code equals}.
 */
public abstract class AbstractBlob implements Blob {

    private static InputSupplier<InputStream> supplier(final Blob blob) {
        return new InputSupplier<InputStream>() {
            @Override
            public InputStream getInput() throws IOException {
                return blob.getNewStream();
            }
        };
    }

    public static boolean equal(Blob a, Blob b) {
        // shortcut: first compare lengths if known in advance
        long al = a.length();
        long bl = b.length();
        if (al != -1 && bl != -1 && al != bl) {
            return false; // blobs not equal, given known and non-equal lengths
        }

        try {
            return ByteStreams.equal(supplier(a), supplier(b));
        } catch (IOException e) {
            throw new IllegalStateException("Blob equality check failed", e);
        }
    }

    public static HashCode calculateSha256(final Blob blob) {
        AbstractBlob ab;
        if (blob instanceof AbstractBlob) {
            ab = ((AbstractBlob) blob);
        } else {
            ab = new AbstractBlob() {
                @Override
                public long length() {
                    return blob.length();
                }
                @Nonnull
                @Override
                public InputStream getNewStream() {
                    return blob.getNewStream();
                }
            };
        }
        return ab.getSha256();
    }

    private HashCode hashCode; // synchronized access

    protected AbstractBlob(HashCode hashCode) {
        this.hashCode = hashCode;
    }

    protected AbstractBlob() {
        this(null);
    }

    private synchronized HashCode getSha256() {
        // Blobs are immutable so we can safely cache the hash
        if (hashCode == null) {
            try {
                hashCode = ByteStreams.hash(supplier(this), Hashing.sha256());
            } catch (IOException e) {
                throw new IllegalStateException("Hash calculation failed", e);
            }
        }
        return hashCode;
    }

    /**
     * This hash code implementation returns the hash code of the underlying stream
     * @return a byte array of the hash
     */
    protected byte[] sha256() {
        return getSha256().asBytes();
    }

    //--------------------------------------------------------------< Blob >--

    @Override @CheckForNull
    public String getReference() {
        return null;
    }

    @Override
    public String getContentIdentity() {
        return null;
    }

//------------------------------------------------------------< Object >--

    /**
     * To {@code Blob} instances are considered equal iff they have the
     * same SHA-256 hash code  are equal.
     * @param other
     */
    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (other instanceof AbstractBlob) {
            AbstractBlob that = (AbstractBlob) other;
            // optimize the comparison if both this and the other blob
            // already have pre-computed SHA-256 hash codes
            synchronized (this) {
                if (hashCode != null) {
                    synchronized (that) {
                        if (that.hashCode != null) {
                            return hashCode.equals(that.hashCode);
                        }
                    }
                }
            }
        }

        return other instanceof Blob && equal(this, (Blob) other);
    }

    @Override
    public int hashCode() {
        return 0; // see Blob javadoc
    }

    @Override
    public String toString() {
        return getSha256().toString();
    }

}
