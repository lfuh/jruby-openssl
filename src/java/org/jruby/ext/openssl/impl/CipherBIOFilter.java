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
 * Copyright (C) 2008 Ola Bini <ola.bini@gmail.com>
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
package org.jruby.ext.openssl.impl;

import java.io.IOException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

/**
 *
 * @author <a href="mailto:ola.bini@gmail.com">Ola Bini</a>
 */
public class CipherBIOFilter extends BIOFilter {
    private Cipher cipher;

    public CipherBIOFilter(Cipher cipher) {
        this.cipher = cipher;
    }

    public void flush() throws IOException {
        try {
            byte[] result = cipher.doFinal();
            if(result == null) {
                return;
            }
            next().write(result, 0, result.length);
        } catch(IllegalBlockSizeException e) {
            throw new PKCS7Exception(-1, -1, e.toString());
        } catch(BadPaddingException e) {
            throw new PKCS7Exception(-1, -1, e.toString());
        }
    }

    public int write(byte[] out, int offset, int len) throws IOException {
        byte[] result = cipher.update(out, offset, len);
        if(result == null) {
            return len;
        }
        next().write(result, 0, result.length);
        return len;
    }

    public int getType() {
        return TYPE_CIPHER;
    }
}// CipherBIOFilter