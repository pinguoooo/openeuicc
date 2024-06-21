// EuiccAidlInterface.aidl
package im.angry.openeuicc;
import android.graphics.Bitmap;
import im.angry.openeuicc.EuiccAidlCallback;

// Declare any non-default types here with import statements

interface EuiccAidlInterface {

    // 通用方法调用
    void callMethod(String methodName, in Map params, EuiccAidlCallback callback);

    // 初始化
    void init(EuiccAidlCallback callback);

    // 刷新通道（esim，sim）列表，或者叫插槽
    void refreshChannel(EuiccAidlCallback callback);

    // 获取所有的通道
    void getAllChannel(EuiccAidlCallback callback);

    // 刷新卡列表
    void refreshSIMCards(EuiccAidlCallback callback);

    // 获取卡列表
    void getAllCards(EuiccAidlCallback callback);

    // 查询卡
    void searchCards(String searchText, EuiccAidlCallback callback);

    // 添加卡 激活码
    void addCardByActvationCode(String actvationCode, EuiccAidlCallback callback);

    // 添加卡 二维码
    void addCardByQrCode(in Bitmap qrCode, EuiccAidlCallback callback);

    // 卡激活
    // ICCID
    void enableCard(String iccid, EuiccAidlCallback callback);

    // 卡取消激活
    // ICCID
    void disableCard(String iccid, EuiccAidlCallback callback);

    // 删除卡
    void deleteCard(String iccid, EuiccAidlCallback callback);

    // 卡重命名
    void renameCard(String iccid, String newName, EuiccAidlCallback callback);
}
