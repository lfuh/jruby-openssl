/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006, 2007 Ola Bini <ola@ologix.com>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.ext.openssl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.BlowfishEngine;
import org.bouncycastle.crypto.engines.CAST5Engine;
import org.bouncycastle.crypto.engines.CAST6Engine;
import org.bouncycastle.crypto.engines.DESEngine;
import org.bouncycastle.crypto.engines.DESedeEngine;
import org.bouncycastle.crypto.engines.RC2Engine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.modes.OFBBlockCipher;
import org.bouncycastle.crypto.paddings.BlockCipherPadding;
import org.bouncycastle.crypto.paddings.ISO10126d2Padding;
import org.bouncycastle.crypto.paddings.ISO7816d4Padding;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.DESParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyObject;
import org.jruby.RubyString;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.Block;
import org.jruby.runtime.CallbackFactory;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;

/**
 * @author <a href="mailto:ola.bini@ki.se">Ola Bini</a>
 */
public class Cipher extends RubyObject {

	// set to enable debug output
	private static final boolean DEBUG = false;
	
	private static ObjectAllocator CIPHER_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new Cipher(runtime, klass);
        }
    };
    
    public static void createCipher(Ruby runtime, RubyModule ossl) {
        RubyModule mCipher = ossl.defineModuleUnder("Cipher");
        RubyClass cCipher = mCipher.defineClassUnder("Cipher",runtime.getObject(), CIPHER_ALLOCATOR);

        RubyClass openSSLError = ossl.getClass("OpenSSLError");
        mCipher.defineClassUnder("CipherError",openSSLError,openSSLError.getAllocator());

        CallbackFactory ciphercb = runtime.callbackFactory(Cipher.class);

        mCipher.getMetaClass().defineFastMethod("ciphers",ciphercb.getFastSingletonMethod("ciphers"));
        cCipher.defineMethod("initialize",ciphercb.getMethod("initialize",IRubyObject.class));
        cCipher.defineFastMethod("initialize_copy",ciphercb.getFastMethod("initialize_copy",IRubyObject.class));
        cCipher.defineFastMethod("name",ciphercb.getFastMethod("name"));
        cCipher.defineFastMethod("key_len",ciphercb.getFastMethod("key_len"));
        cCipher.defineFastMethod("key_len=",ciphercb.getFastMethod("set_key_len",IRubyObject.class));
        cCipher.defineFastMethod("iv_len",ciphercb.getFastMethod("iv_len"));
        cCipher.defineFastMethod("block_size",ciphercb.getFastMethod("block_size"));
        cCipher.defineFastMethod("encrypt",ciphercb.getFastOptMethod("encrypt"));
        cCipher.defineFastMethod("decrypt",ciphercb.getFastOptMethod("decrypt"));
        cCipher.defineFastMethod("key=",ciphercb.getFastMethod("set_key",IRubyObject.class));
        cCipher.defineFastMethod("iv=",ciphercb.getFastMethod("set_iv",IRubyObject.class));
        cCipher.defineFastMethod("reset",ciphercb.getFastMethod("reset"));
        cCipher.defineFastMethod("pkcs5_keyivgen",ciphercb.getFastOptMethod("pkcs5_keyivgen"));
        cCipher.defineFastMethod("update",ciphercb.getFastMethod("update",IRubyObject.class));
        cCipher.defineFastMethod("<<",ciphercb.getFastMethod("update_deprecated",IRubyObject.class));
        cCipher.defineFastMethod("final",ciphercb.getFastMethod("_final"));
        cCipher.defineFastMethod("padding=",ciphercb.getFastMethod("set_padding",IRubyObject.class));
    }

    private static final Set BLOCK_MODES = new HashSet();
    static {
        BLOCK_MODES.add("CBC");
        BLOCK_MODES.add("CFB");
        BLOCK_MODES.add("CFB1");
        BLOCK_MODES.add("CFB8");
        BLOCK_MODES.add("ECB");
        BLOCK_MODES.add("OFB");
    }

    private static String[] rubyToJavaCipher(String inName, String padding) {
        String[] split = inName.split("-");
        String cryptoBase = split[0];
        String cryptoVersion = null;
        String cryptoMode = null;
        String realName = null;

        String padding_type;
        if (padding == null || padding.equalsIgnoreCase("PKCS5Padding")) {
            padding_type = "PKCS5Padding";
        } else if (padding.equals("0") || padding.equalsIgnoreCase("NoPadding")) {
            padding_type = "NoPadding";
        } else if (padding.equalsIgnoreCase("ISO10126Padding")) {
            padding_type = "ISO10126Padding";
        } else {
            padding_type = "PKCS5Padding";
        }

        if("bf".equalsIgnoreCase(cryptoBase)) {
            cryptoBase = "Blowfish";
        }

        if(split.length == 3) {
            cryptoVersion = split[1];
            cryptoMode = split[2];
        } else {
            if(split.length == 2) {
                cryptoMode = split[1];
            } else {
                cryptoMode = "ECB";
            }
        }

        if(cryptoBase.equalsIgnoreCase("DES") && "EDE3".equalsIgnoreCase(cryptoVersion)) {
            realName = "DESede";
        } else {
            realName = cryptoBase;
        }

        if(!BLOCK_MODES.contains(cryptoMode.toUpperCase())) {
            cryptoVersion = cryptoMode;
            cryptoMode = "CBC";
        }

        realName = realName + "/" + cryptoMode + "/" + padding_type;

        return new String[]{cryptoBase,cryptoVersion,cryptoMode,realName,padding_type};
    }

    private static boolean tryCipher(String rubyName) {
        try {
            javax.crypto.Cipher.getInstance(rubyToJavaCipher(rubyName, null)[3],OpenSSLReal.PROVIDER);
            return true;
        } catch(Exception e) {
            return false;
        }
    }

    public static IRubyObject ciphers(IRubyObject recv) {
        List ciphers = new ArrayList();
        String[] other = {"AES128","AES192","AES256","BLOWFISH", "RC2-40-CBC", "RC2-64-CBC","RC4","RC4-40", "CAST","CAST-CBC"};
        String[] bases = {"AES-128","AES-192","AES-256","BF", "DES", "DES-EDE","DES-EDE3", "RC2","CAST5"};
        String[] suffixes = {"","-CBC","-CFB","-CFB1","-CFB8","-ECB","-OFB"};
        for(int i=0,j=bases.length;i<j;i++) {
            for(int k=0,l=suffixes.length;k<l;k++) {
                String val = bases[i]+suffixes[k];
                if(tryCipher(val)) {
                    ciphers.add(recv.getRuntime().newString(val));
                    ciphers.add(recv.getRuntime().newString((val).toLowerCase()));
                }
            }
        }
        for(int i=0,j=other.length;i<j;i++) {
            if(tryCipher(other[i])) {
                ciphers.add(recv.getRuntime().newString(other[i]));
                ciphers.add(recv.getRuntime().newString(other[i].toLowerCase()));
            }
        }
        return recv.getRuntime().newArray(ciphers);
    }
    
    public static BlockCipherPadding getBlockCipherPadding(String pad) {
        // get appropriate padding
        if (pad.equals("PKCS5Padding")) {
            return new PKCS7Padding();
        } else if (pad.equals("PKCS7Padding")) {
            return new PKCS7Padding();
        } else if (pad.equals("ISO10126Padding")) {
            return new ISO10126d2Padding();
        } else if (pad.equals("ISO7816Padding")) {
            return new ISO7816d4Padding();
        } else if (pad.equals("NoPadding")) {
            return null;
        } else {
            // FIXME should be a ruby exception
            throw new RuntimeException("Unknown padding: " + pad);
        }
    }
    
    public static BlockCipher getBlockCipher(String cipherBase, String cipherVersion, String cipherMode) {
        BlockCipher cipher;

        // get base cipher
        if (cipherBase.equals("AES")) {
            cipher = new AESEngine();
        } else if (cipherBase.equals("aes")) {
            cipher = new AESEngine();
        } else if (cipherBase.equals("DES") || cipherBase.equals("des")) {
            if ("EDE3".equals(cipherVersion) || "ede3".equals(cipherVersion)) { 
                cipher = new DESedeEngine();
            } else {
                cipher = new DESEngine();
            }
        } else if (cipherBase.equals("Blowfish")) {
            cipher = new BlowfishEngine();
        } else if (cipherBase.equals("BLOWFISH")) {
            cipher = new BlowfishEngine();
        } else if (cipherBase.equals("blowfish")) {
            cipher = new BlowfishEngine();
        } else if (cipherBase.equals("RC2")) {
            cipher = new RC2Engine();
        } else if (cipherBase.equals("rc2")) {
            cipher = new RC2Engine();
        } else if (cipherBase.equals("CAST5")) {
            cipher = new CAST5Engine();
        } else if (cipherBase.equals("cast5")) {
            cipher = new CAST5Engine();
        } else if (cipherBase.equals("CAST6")) {
            cipher = new CAST6Engine();
        } else if (cipherBase.equals("cast6")) {
            cipher = new CAST6Engine();
        } else {
            // FIXME should be a ruby exception
            return null;
        }

        // see http://csrc.nist.gov/publications/nistpubs/800-38a/sp800-38a.pdf
        // for a good, widely-cited (if not necessarily definitive) description
        // of block cipher modes, with test inputs/outputs.
        // (however, it doesn't answer questions about the BC implementation)

        // Wrap with mode-specific cipher
        if (cipherMode.equalsIgnoreCase("CBC")) {
            cipher = new CBCBlockCipher(cipher);
        } else if (cipherMode.equalsIgnoreCase("CFB")) {
            // FIXME: I have no number to put here! I'm using 8.
            cipher = new CFBBlockCipher(cipher, 8);
        } else if (cipherMode.equalsIgnoreCase("CFB1")) {
            // FIXME: Does 1 mean 1 * 8?
            // BD: '1' means '1' (bit), but reportedly (and apparently,
            // from a look at the code) this is not supported by BC,
            // which only supports multiples of 8.
            cipher = new CFBBlockCipher(cipher, 1); // this will fail
        } else if (cipherMode.equalsIgnoreCase("CFB8")) {
            // FIXME: Does 8 mean 8 * 8?
            // BD: '8' means '8' (bits).  other common CFB modes are 64 and 128
            cipher = new CFBBlockCipher(cipher, 8);
        } else if (cipherMode.equalsIgnoreCase("OFB")) {
            // FIXME: I have no number to put here! I'm using 8.
            cipher = new OFBBlockCipher(cipher, 8);
        }

        return cipher;
    }
    
    public static BufferedBlockCipher getCipher(Ruby runtime, String cipherBase, String cipherVersion, String cipherMode, String cipherPad) {
        BlockCipherPadding padding = getBlockCipherPadding(cipherPad);
        BlockCipher cipher = getBlockCipher(cipherBase, cipherVersion, cipherMode);
        
        if (cipher != null) {
            if (!"ECB".equalsIgnoreCase(cipherMode) && padding != null) {
                return new PaddedBufferedBlockCipher(cipher, padding);
            } else {
                return new BufferedBlockCipher(cipher);
            }
        } else {
            throw runtime.newLoadError("unsupported cipher algorithm (" + cipherBase + "-" + cipherMode + "-" + cipherPad + ")");
        }
    }
    
    public static KeyParameter getKeyParameter(String cipherBase, byte[] key) {
        if (cipherBase.equals("DES") || cipherBase.equals("des")) {
            return new DESParameters(key);
        } else {
            return new KeyParameter(key);
        }
    }

    private RubyClass ciphErr;
    public Cipher(Ruby runtime, RubyClass type) {
        super(runtime,type);
        ciphErr = (RubyClass)(((RubyModule)(getRuntime().getModule("OpenSSL").getConstant("Cipher"))).getConstant("CipherError"));
    }

    private BufferedBlockCipher ciph;
    private String name;
    private String cryptoBase;
    private String cryptoVersion;
    private String cryptoMode;
    private String padding_type;
    private String realName;
    private int keyLen = -1;
    private int ivLen = -1;
    private boolean encryptMode = true;
    //private IRubyObject[] modeParams;
    private boolean ciphInited = false;
    private byte[] key;
    private byte[] iv;
    private String padding;
    
    private void dumpVars() {
        System.out.println("***** Cipher instance vars ****");
        System.out.println("name = " + name);
        System.out.println("cryptoBase = " + cryptoBase);
        System.out.println("cryptoVersion = " + cryptoVersion);
        System.out.println("cryptoMode = " + cryptoMode);
        System.out.println("padding_type = " + padding_type);
        System.out.println("realName = " + realName);
        System.out.println("keyLen = " + keyLen);
        System.out.println("ivLen = " + ivLen);
        System.out.println("ciph block size = " + ciph.getBlockSize());
        System.out.println("encryptMode = " + encryptMode);
        System.out.println("ciphInited = " + ciphInited);
        System.out.println("key.length = " + (key == null ? 0 : key.length));
        System.out.println("iv.length = " + (iv == null ? 0 : iv.length));
        System.out.println("padding = " + padding);
        System.out.println("*******************************");
    }

    public IRubyObject initialize(IRubyObject str, Block unusedBlock) {
        name = str.toString();
        String[] values = rubyToJavaCipher(name, padding);
        cryptoBase = values[0];
        cryptoVersion = values[1];
        cryptoMode = values[2];
        realName = values[3];
        padding_type = values[4];

        ciph = getCipher(getRuntime(), cryptoBase, cryptoVersion, cryptoMode, padding_type);

        if(hasLen() && null != cryptoVersion) {
            try {
                keyLen = Integer.parseInt(cryptoVersion) / 8;
            } catch(NumberFormatException e) {
                keyLen = -1;
            }
        }

        if(keyLen == -1) {
            if("DES".equalsIgnoreCase(cryptoBase)) {
                ivLen = 8;
                if("EDE3".equalsIgnoreCase(cryptoVersion)) {
                    keyLen = 24;
                } else {
                    keyLen = 8;
                }
            } else {
                keyLen = 16;
            }
        }

        if(ivLen == -1) {
            if("AES".equalsIgnoreCase(cryptoBase)) {
                ivLen = 16;
            } else {
                ivLen = 8;
            }
        }

        return this;
    }

    public IRubyObject initialize_copy(IRubyObject obj) {
        if(this == obj) {
            return this;
        }

        checkFrozen();

        cryptoBase = ((Cipher)obj).cryptoBase;
        cryptoVersion = ((Cipher)obj).cryptoVersion;
        cryptoMode = ((Cipher)obj).cryptoMode;
        padding_type = ((Cipher)obj).padding_type;
        realName = ((Cipher)obj).realName;
        name = ((Cipher)obj).name;
        keyLen = ((Cipher)obj).keyLen;
        ivLen = ((Cipher)obj).ivLen;
        encryptMode = ((Cipher)obj).encryptMode;
        ciphInited = false;
        if(((Cipher)obj).key != null) {
            key = new byte[((Cipher)obj).key.length];
            System.arraycopy(((Cipher)obj).key,0,key,0,key.length);
        } else {
            key = null;
        }
        if(((Cipher)obj).iv != null) {
            iv = new byte[((Cipher)obj).iv.length];
            System.arraycopy(((Cipher)obj).iv,0,iv,0,iv.length);
        } else {
            iv = null;
        }
        padding = ((Cipher)obj).padding;

        ciph = getCipher(getRuntime(), cryptoBase, cryptoVersion, cryptoMode, padding_type);

        return this;
    }

    public IRubyObject name() {
        return getRuntime().newString(name);
    }

    public IRubyObject key_len() {
        return getRuntime().newFixnum(keyLen);
    }

    public IRubyObject iv_len() {
        return getRuntime().newFixnum(ivLen);
    }

    public IRubyObject set_key_len(IRubyObject len) {
        this.keyLen = RubyNumeric.fix2int(len);
        return len;
    }

    public IRubyObject set_key(IRubyObject key) {
        byte[] keyBytes;
        try {
            keyBytes = key.convertToString().getBytes();
        } catch(Exception e) {
            e.printStackTrace();
            throw new RaiseException(getRuntime(), ciphErr, null, true);
        }
        if(keyBytes.length < keyLen) {
            throw new RaiseException(getRuntime(), ciphErr, "key length to short", true);
        }
        this.key = keyBytes;
        return key;
    }

    public IRubyObject set_iv(IRubyObject iv) {
        byte[] ivBytes;
        try {
            ivBytes = iv.convertToString().getBytes();
        } catch(Exception e) {
            e.printStackTrace();
            throw new RaiseException(getRuntime(), ciphErr, null, true);
        }
        if(ivBytes.length < ivLen) {
            throw new RaiseException(getRuntime(), ciphErr, "iv length to short", true);
        }
        this.iv = ivBytes;
        return iv;
    }

    public IRubyObject block_size() {
        return getRuntime().newFixnum(ciph.getBlockSize());
    }

    public IRubyObject encrypt(IRubyObject[] args) {
        //TODO: implement backwards compat
        org.jruby.runtime.Arity.checkArgumentCount(getRuntime(),args,0,2);
        encryptMode = true;
        //modeParams = args;
        ciphInited = false;
        return this;
    }

    public IRubyObject decrypt(IRubyObject[] args) {
        //TODO: implement backwards compat
        org.jruby.runtime.Arity.checkArgumentCount(getRuntime(),args,0,2);
        encryptMode = false;
        //modeParams = args;
        ciphInited = false;
        return this;
    }

    public IRubyObject reset() {
        doInitialize();
        return this;
    }

    private boolean hasLen() {
        return hasLen(this.cryptoBase);
    }

    private static boolean hasLen(String cryptoBase) {
        return "AES".equalsIgnoreCase(cryptoBase) || "RC2".equalsIgnoreCase(cryptoBase) || "RC4".equalsIgnoreCase(cryptoBase);
    }

    public IRubyObject pkcs5_keyivgen(IRubyObject[] args) {
        org.jruby.runtime.Arity.checkArgumentCount(getRuntime(),args,1,4);
        byte[] pass = args[0].convertToString().getBytes();
        byte[] salt = null;
        int iter = 2048;
        IRubyObject vdigest = getRuntime().getNil();
        org.bouncycastle.crypto.Digest digest = null;
        if(args.length>1) {
            if(!args[1].isNil()) {
                salt = args[1].convertToString().getBytes();;
            }
            if(args.length>2) {
                if(!args[2].isNil()) {
                    iter = RubyNumeric.fix2int(args[2]);
                }
                if(args.length>3) {
                    vdigest = args[3];
                }
            }
        }
        try {
            if(null != salt) {
                if(salt.length != 8) {
                    throw new RaiseException(getRuntime(), ciphErr, "salt must be an 8-octet string", true);
                }
            }
            if(vdigest.isNil()) {
                digest = Digest.getDigest(getRuntime(), "MD5");
            } else {
                digest = Digest.getDigest(getRuntime(), ((Digest)vdigest).getAlgorithm());
            }

            OpenSSLImpl.KeyAndIv result = OpenSSLImpl.EVP_BytesToKey(keyLen,ivLen,digest,salt,pass,iter);
            this.key = result.getKey();
            this.iv = result.getIv();
        } catch(Exception e) {
            e.printStackTrace();
            throw new RaiseException(getRuntime(), ciphErr, null, true);
        }

        doInitialize();

        return getRuntime().getNil();
    }

    private void doInitialize() {

        if (DEBUG) System.out.println("*** doInitialize");
        if (DEBUG) dumpVars();

        ciphInited = true;
        try {
            // FIXME: I had to make these >= where they were == before; why?

            assert key.length >= keyLen : "Key wrong length";
            assert iv.length >= ivLen : "IV wrong length";
            if(!"ECB".equalsIgnoreCase(cryptoMode) && this.iv != null) {
                this.ciph.init(encryptMode, new ParametersWithIV(getKeyParameter(cryptoBase, key), iv));
            } else {
                this.ciph.init(encryptMode, getKeyParameter(cryptoBase, key));
            }
        } catch(Exception e) {
            e.printStackTrace();
            throw new RaiseException(getRuntime(), ciphErr, null, true);
        }
    }

    public IRubyObject update(IRubyObject data) {
        if (DEBUG) System.out.println("*** update ["+data+"]");

        //TODO: implement correctly
        byte[] val = data.convertToString().getBytes();
        if(val.length == 0) {
            throw getRuntime().newArgumentError("data must not be empty");
        }

        if(!ciphInited) {
            doInitialize();
        }

        byte[] str = new byte[0];
        int count;
        try {
            byte[] out = new byte[ciph.getUpdateOutputSize(val.length)];
            count = ciph.processBytes(val, 0, val.length, out, 0);
            if(count != 0) {
                str = out;
            }
        } catch(Exception e) {
            e.printStackTrace();
            throw new RaiseException(getRuntime(), ciphErr, null, true);
        }

        return RubyString.newString(getRuntime(), new ByteList(str, 0, count, false));
    }

    public IRubyObject update_deprecated(IRubyObject data) {
        getRuntime().getWarnings().warn("" + this.getMetaClass().getRealClass().getName() + "#<< is deprecated; use " + this.getMetaClass().getRealClass().getName() + "#update instead");
        return update(data);
    }

    public IRubyObject _final() {
        if(!ciphInited) {
            doInitialize();
        }

        //TODO: implement correctly
        ByteList str = new ByteList(ByteList.NULL_ARRAY);
        int count;
        try {
            byte[] out = new byte[ciph.getOutputSize(0)];
            count = ciph.doFinal(out, 0);
            if(out != null) {
                str = new ByteList(out,false);
            }
        } catch(Exception e) {
            e.printStackTrace();
            throw new RaiseException(getRuntime(), ciphErr, null, true);
        }

        return getRuntime().newString(new ByteList(str, 0, count));
    }

    public IRubyObject set_padding(IRubyObject padding) {
        this.padding = padding.toString();
        initialize(RubyString.newString(getRuntime(), name), Block.NULL_BLOCK);
        return padding;
    }

    String getAlgorithm() {
        return this.ciph.getUnderlyingCipher().getAlgorithmName();
    }
}

