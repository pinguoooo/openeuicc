// EuiccAidlCallback.aidl
package im.angry.openeuicc;
import im.angry.openeuicc.AidlResult;

// Declare any non-default types here with import statements

interface EuiccAidlCallback {
    void onResult(in AidlResult result);
}