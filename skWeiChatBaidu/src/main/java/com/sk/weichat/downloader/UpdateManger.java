package com.sk.weichat.downloader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;

import com.sk.weichat.MyApplication;
import com.sk.weichat.R;
import com.sk.weichat.Reporter;
import com.sk.weichat.util.LogUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


/**
 * Created by zq on 2016/5/31.
 */
public class UpdateManger {

    // 通知处理刷新界面的handler
    private Handler mHandler = new Handler() {
        @SuppressLint("HandlerLeak")
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DOWN_UPDATE:
                    mProgress.setProgress(progress);
                    break;
                case DOWN_OVER:
                    installApk();
                    try {
                        noticeDialog.dismiss();
                        downloadDialog.dismiss();
                    } catch (Throwable t) {
                        Reporter.unreachable();
                    }
                    break;
            }
            super.handleMessage(msg);
        }
    };

    // 应用程序Context
    private Context mContext;
    // 提示消息
    private String updateMsg = MyApplication.getContext().getString(R.string.new_apk_download);
    // 下载安装包的网络路径
    // private String apkUrl = "http://www.eoemarket.com/download/792805_0";
    private String apkUrl;
    private Dialog noticeDialog;// 提示有软件更新的对话框
    private Dialog downloadDialog;// 下载对话框
    private static final String savePath = "/sdcard/updatedemo/";// 保存apk的文件夹
    private static final String saveFileName = savePath + "UpdateDemoRelease.apk";
    // 进度条与通知UI刷新的handler和msg常量
    private ProgressBar mProgress;
    private static final int DOWN_UPDATE = 1;
    private static final int DOWN_OVER = 2;
    private int progress;// 当前进度
    private Thread downLoadThread; // 下载线程
    private boolean interceptFlag = false;// 用户取消下载
    private Runnable mdownApkRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build();
                Request request = new Request.Builder()
                        .url(apkUrl)
                        .build();
                Response response = client.newCall(request)
                        .execute();
                response.body().contentLength();
                long length = response.body().contentLength();
                InputStream ins = response.body().byteStream();
                File file = new File(savePath);
                if (!file.exists()) {
                    file.mkdir();
                }
                String apkFile = saveFileName;
                File ApkFile = new File(apkFile);
                FileOutputStream outStream = new FileOutputStream(ApkFile);
                int count = 0;
                byte buf[] = new byte[1024];
                do {
                    int numread = ins.read(buf);
                    count += numread;
                    progress = (int) (((float) count / length) * 100);
                    // 下载进度
                    mHandler.sendEmptyMessage(DOWN_UPDATE);
                    if (numread <= 0) {
                        // 下载完成通知安装
                        mHandler.sendEmptyMessage(DOWN_OVER);
                        break;
                    }
                    outStream.write(buf, 0, numread);
                } while (!interceptFlag);// 点击取消停止下载
                outStream.close();
                ins.close();
            } catch (Exception e) {
                Reporter.unreachable(e);
                callNoUpdate();
            }
        }
    };
    updateListener mUpdateListener;

    public interface updateListener {
        void noUpdate();
    }

    public UpdateManger(Context context, updateListener updateListener) {
        this.mContext = context;
        this.mUpdateListener = updateListener;
    }

    public static void checkUpdate(Activity ctx, String updateUrl, String version) {
        if (TextUtils.isEmpty(version) || TextUtils.isEmpty(updateUrl)) {
            // 服务器没有配置新版本，
            return;
        }
        try {
            // 保留旧代码，
            UpdateManger manger = new UpdateManger(ctx, () -> {
                LogUtils.log("取消了更新，");
            });
            manger.checkUpdateInfo(version, updateUrl);
        } catch (Throwable t) {
            // 无论如何不能因为这个崩溃，
            Reporter.post("检查更新失败，", t);
        }
    }

    // 显示更新程序对话框，供主程序调用
    public void checkUpdateInfo(String serviceVersionCode, String apkUrls) {//添加检查服务器更新的代码
        PackageManager mPackageManager = mContext.getPackageManager();
        PackageInfo packageInfo = null;
        try {
            packageInfo = mPackageManager.getPackageInfo(mContext.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Reporter.unreachable(e);
            callNoUpdate();
            return;
        }
        String versionName = packageInfo.versionName;

        String mCurrentVersionCode = versionName.replaceAll("\\.", "");
        if (mCurrentVersionCode.compareTo(serviceVersionCode) >= 0) {// 版本号不低 不需要更新
            callNoUpdate();
        } else {
            apkUrl = apkUrls;
            showNoticeDialog(getStringById(R.string.application_version_update_down), getStringById(R.string.application_update));
        }
    }

    private String getStringById(int sid) {
        return MyApplication.getContext().getString(sid);
    }

    private void callNoUpdate() {
        if (mUpdateListener != null) {
            mHandler.post(() -> {
                if (mUpdateListener != null) {
                    mUpdateListener.noUpdate();
                    // 确保只调用一次，
                    mUpdateListener = null;
                }
            });
        }
    }

    private void showNoticeDialog(String desc, String title) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(
                mContext);// Builder，可以通过此builder设置改变AleartDialog的默认的主题样式及属性相关信息
        builder.setTitle(title);
        if (TextUtils.isEmpty(desc)) {
            builder.setMessage(updateMsg);
        } else {
            builder.setMessage(desc);
        }
        builder.setPositiveButton(MyApplication.getContext().getString(R.string.download), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();// 当取消对话框后进行操作一定的代码？取消对话框
                try {
                    showDownloadDialog();
                } catch (Throwable t) {
                    Reporter.unreachable(t);
                    callNoUpdate();
                }
            }
        });
        builder.setNegativeButton(MyApplication.getContext().getString(R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        builder.setOnDismissListener(dialog -> {
            callNoUpdate();
        });
        noticeDialog = builder.create();
        noticeDialog.setCanceledOnTouchOutside(false);
        noticeDialog.setCancelable(false);
        noticeDialog.show();
    }

    private void downloadApk() {
        downLoadThread = new Thread(mdownApkRunnable);
        downLoadThread.start();
    }

    protected void installApk() {
        File apkfile = new File(saveFileName);
        if (!apkfile.exists()) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse("file://" + apkfile.toString()),
                "application/vnd.android.package-archive");// File.toString()会返回路径信息
        mContext.startActivity(i);
    }

    protected void showDownloadDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(mContext);
        builder.setTitle(R.string.application_update);
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        View v = inflater.inflate(R.layout.progress, null);
        mProgress = (ProgressBar) v.findViewById(R.id.progress);
        builder.setView(v);// 设置对话框的内容为一个View
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                interceptFlag = true;
                callNoUpdate();
            }
        });
        downloadDialog = builder.create();
        downloadDialog.show();
        downloadApk();
    }
}
