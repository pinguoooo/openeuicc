package im.angry.openeuicc;

import android.os.Parcel;
import android.os.Parcelable;

public class AidlResult implements Parcelable {

    /**
     * 请求状态
     * 0: 未知/默认
     * 1: 正常
     */
    private int state;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 返回数据格式：json
     */
    private String data;

    /**
     * 附带消息
     */
    private String msg;

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }


    public AidlResult() {
        this.state = 0;
        this.data = "";
        this.msg = "";
    }

    public AidlResult(int state) {
        this.state = state;
        this.data = "";
        this.msg = "";
    }

    public AidlResult(int state,String data) {
        this.state = state;
        this.data = data;
        this.msg = "";
    }
    public AidlResult(int state, String data, String msg) {
        this.state = state;
        this.data = data;
        this.msg = msg;
    }

    public AidlResult(int state, String data, String msg,String methodName) {
        this.state = state;
        this.data = data;
        this.msg = msg;
        this.methodName = methodName;
    }

    protected AidlResult(Parcel in) {
        state = in.readInt();
        methodName = in.readString();
        data = in.readString();
        msg = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(state);
        dest.writeString(methodName);
        dest.writeString(data);
        dest.writeString(msg);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AidlResult> CREATOR = new Creator<AidlResult>() {
        @Override
        public AidlResult createFromParcel(Parcel in) {
            return new AidlResult(in);
        }

        @Override
        public AidlResult[] newArray(int size) {
            return new AidlResult[size];
        }
    };

    @Override
    public String toString() {
        return "AidlResult{" +
                "state=" + state +
                ", methodName='" + methodName + '\'' +
                ", data='" + data + '\'' +
                ", msg='" + msg + '\'' +
                '}';
    }
}
