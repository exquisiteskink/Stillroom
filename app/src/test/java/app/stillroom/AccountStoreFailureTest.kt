package app.stillroom

import app.stillroom.data.provesRecordCorruption
import java.io.EOFException
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import org.json.JSONException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host suite cannot construct Android Keystore, so this pins the decision that decides
 * whether `EncryptedAccountStore.list()` deletes a saved account: only proof of damaged bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccountStoreFailureTest {
    @Test fun damagedRecordBytesAreCorruption() {
        assertTrue(provesRecordCorruption(AEADBadTagException("tag mismatch")))
        assertTrue(provesRecordCorruption(JSONException("bad json")))
        assertTrue(provesRecordCorruption(EOFException()))
        assertTrue(provesRecordCorruption(IllegalArgumentException("Account identity mismatch.")))
    }

    @Test fun keystoreAndProviderFailuresNeverDeleteTheAccount() {
        assertFalse(provesRecordCorruption(ProviderException("Keystore operation failed")))
        assertFalse(provesRecordCorruption(KeyStoreException("system error")))
        assertFalse(provesRecordCorruption(InvalidKeyException("key unusable")))
        assertFalse(provesRecordCorruption(UnrecoverableKeyException("locked")))
        assertFalse(provesRecordCorruption(IllegalStateException("Cipher not initialized")))
        assertFalse(provesRecordCorruption(IOException("storage")))
    }
}
