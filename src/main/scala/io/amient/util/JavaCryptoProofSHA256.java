/*
 * Copyright 2016 Michal Harish, michal.harish@gmail.com
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.amient.util;

import javax.xml.bind.DatatypeConverter;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class JavaCryptoProofSHA256 extends  JavaCryptoProof {
    final byte[] salt;

    public JavaCryptoProofSHA256(byte[] salt) {
        this.salt = salt;
    }

    public JavaCryptoProofSHA256(String hexSalt) {
        this(JavaCryptoProof.fromHex(hexSalt));
    }


    @Override
    protected byte[] sign(byte[] arg) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] input = new byte[salt.length + arg.length];
        System.arraycopy(salt, 0, input, 0, salt.length);
        System.arraycopy(arg, 0, input, salt.length, arg.length);
        return digest.digest(input);
    }


}