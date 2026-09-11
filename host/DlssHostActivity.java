package com.winlator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.FileUtils;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Android host for the user-supplied DLSS5ForAll Windows package.
 *
 * The proprietary NVIDIA/ReShade pieces are never bundled. The user imports the package
 * they already own. Android MediaProjection replaces Windows Graphics Capture only at the
 * capture boundary; AndroidFramePresenter.exe then creates the same kind of D3D11 Present
 * surface that the original ReShade/DLSS5 chain expects.
 */
public final class DlssHostActivity extends AppCompatActivity {
    private static final int REQ_PACKAGE = 5010;
    private static final int REQ_CAPTURE = 5011;
    private static final String CONTAINER_NAME = "Android DLSS Host";
    private static final String INSTALL_DIR = "DLSS5ForAll";
    private static final String BRIDGE_EXE = "AndroidFramePresenter.exe";
    private static final String FRAME_FILE = "android-frame.bin";

    private final Handler handler = new Handler();
    private TextView status;
    private Button importButton;
    private Button captureButton;
    private Button bridgeButton;
    private Button launchButton;
    private Button diagnosticsButton;
    private Container container;
    private File packageDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        prepareRuntime();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(42, 42, 42, 72);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(12, 14, 18));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Android DLSS Host");
        title.setTextColor(Color.WHITE);
        title.setTextSize(27f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView info = new TextView(this);
        info.setText("Port experimental do DLSS5ForAll para Android.\n\n" +
                "O app mantém a cadeia Windows/ReShade/DLSS5 do pacote original, mas troca a captura WGC por MediaProjection. " +
                "Os frames Android entram no Wine por um presenter D3D11 aberto e auditável.\n\n" +
                "O APK não inclui nvngx_dlssnr.dll, nvngx_dlss.dll, ReShade ou outros binários proprietários: importe o ZIP que você já possui.");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(16f);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(0, 28, 0, 28);
        root.addView(info, ip);

        importButton = addButton(root, "1. IMPORTAR DLSS5FORALL-PORTABLE.ZIP");
        importButton.setEnabled(false);
        importButton.setOnClickListener(v -> choosePackage());

        captureButton = addButton(root, "2. ATIVAR CAPTURA DO ANDROID");
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(v -> requestAndroidCapture());

        bridgeButton = addButton(root, "3. ABRIR CAPTURA NA CADEIA DLSS5");
        bridgeButton.setEnabled(false);
        bridgeButton.setOnClickListener(v -> launchAndroidBridge());

        launchButton = addButton(root, "TESTAR DLSS5FORALL.EXE ORIGINAL");
        launchButton.setEnabled(false);
        launchButton.setOnClickListener(v -> launchWindowsProgram());

        diagnosticsButton = addButton(root, "VER DIAGNÓSTICO");
        diagnosticsButton.setEnabled(false);
        diagnosticsButton.setOnClickListener(v -> showDiagnostics());

        status = new TextView(this);
        status.setText("Preparando Wine/Box64…");
        status.setTextColor(Color.rgb(120, 220, 160));
        status.setTextSize(15f);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, 28, 0, 0);
        root.addView(status, sp);

        setContentView(scroll);
    }

    private Button addButton(LinearLayout root, String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, root.getChildCount() > 2 ? 14 : 0, 0, 0);
        root.addView(b, p);
        return b;
    }

    private void prepareRuntime() {
        RootFS root = RootFS.find(this);
        if (root.isValid() && root.getVersion() >= RootFSInstaller.LATEST_VERSION) {
            ensureContainer();
            return;
        }

        status.setText("Instalando o ambiente Wine/Box64 interno. Na primeira vez isso pode demorar.");
        importButton.setEnabled(false);
        RootFSInstaller.installIfNeeded(this);
        pollRootFs(0);
    }

    private void pollRootFs(int attempt) {
        RootFS root = RootFS.find(this);
        if (root.isValid() && root.getVersion() >= RootFSInstaller.LATEST_VERSION) {
            ensureContainer();
            return;
        }
        if (attempt > 600) {
            status.setText("O ambiente Windows não terminou de instalar. Feche e abra o app para tentar novamente.");
            return;
        }
        handler.postDelayed(() -> pollRootFs(attempt + 1), 1000);
    }

    private void ensureContainer() {
        status.setText("Preparando contêiner Windows…");
        ContainerManager manager = new ContainerManager(this);
        for (Container candidate : manager.getContainers()) {
            if (CONTAINER_NAME.equals(candidate.getName())) {
                container = candidate;
                onContainerReady();
                return;
            }
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", "1280x720");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("envVars", Container.DEFAULT_ENV_VARS + " DXVK_LOG_LEVEL=info");
            manager.createContainerAsync(data, created -> {
                container = created;
                if (container == null) {
                    status.setText("Falha ao criar o contêiner Windows.");
                    return;
                }
                onContainerReady();
            });
        }
        catch (Exception e) {
            status.setText("Falha ao preparar contêiner: " + e.getMessage());
        }
    }

    private void onContainerReady() {
        packageDir = new File(container.getRootDir(), ".wine/drive_c/" + INSTALL_DIR);
        File exe = new File(packageDir, "DLSS5ForAll.exe");
        importButton.setEnabled(true);
        if (exe.isFile()) {
            try {
                installBridgeBinary();
                enablePackageActions();
                status.setText("Runtime e pacote encontrados. A ponte Android está pronta para teste.");
            } catch (Exception e) {
                status.setText("Pacote encontrado, mas a ponte não pôde ser instalada: " + e.getMessage());
                launchButton.setEnabled(true);
                diagnosticsButton.setEnabled(true);
            }
        } else {
            status.setText("Runtime pronto. Agora selecione o ZIP original do DLSS5ForAll.");
        }
    }

    private void enablePackageActions() {
        captureButton.setEnabled(true);
        bridgeButton.setEnabled(new File(packageDir, BRIDGE_EXE).isFile());
        launchButton.setEnabled(new File(packageDir, "DLSS5ForAll.exe").isFile());
        diagnosticsButton.setEnabled(true);
    }

    private void choosePackage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "application/x-zip-compressed"});
        startActivityForResult(i, REQ_PACKAGE);
    }

    private void requestAndroidCapture() {
        if (packageDir == null || !new File(packageDir, BRIDGE_EXE).isFile()) {
            Toast.makeText(this, "Importe o pacote primeiro.", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PACKAGE && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            catch (Exception ignored) {}
            importPackage(uri);
            return;
        }

        if (requestCode == REQ_CAPTURE) {
            if (resultCode != Activity.RESULT_OK || data == null) {
                status.setText("Captura Android não autorizada.");
                return;
            }
            Intent svc = new Intent(this, AndroidFrameBridgeService.class);
            svc.putExtra(AndroidFrameBridgeService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(AndroidFrameBridgeService.EXTRA_RESULT_DATA, data);
            svc.putExtra(AndroidFrameBridgeService.EXTRA_FRAME_PATH, new File(packageDir, FRAME_FILE).getAbsolutePath());
            ContextCompat.startForegroundService(this, svc);
            status.setText("Captura Android ativa. Agora abra a ponte D3D11 para alimentar a cadeia original.");
            bridgeButton.setEnabled(true);
        }
    }

    private void importPackage(Uri uri) {
        if (container == null) return;
        importButton.setEnabled(false);
        captureButton.setEnabled(false);
        bridgeButton.setEnabled(false);
        launchButton.setEnabled(false);
        status.setText("Extraindo o pacote original para o contêiner Windows…");

        Executors.newSingleThreadExecutor().execute(() -> {
            String error = null;
            try {
                if (packageDir.exists()) FileUtils.delete(packageDir);
                if (!packageDir.mkdirs() && !packageDir.isDirectory()) throw new Exception("não foi possível criar a pasta de destino");

                try (InputStream raw = getContentResolver().openInputStream(uri);
                     ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw))) {
                    ZipEntry entry;
                    byte[] buffer = new byte[128 * 1024];
                    String canonicalRoot = packageDir.getCanonicalPath() + File.separator;
                    while ((entry = zin.getNextEntry()) != null) {
                        String name = entry.getName().replace('\\', '/');
                        if (name.startsWith("/") || name.contains("../")) throw new SecurityException("entrada ZIP inválida: " + name);
                        File out = new File(packageDir, name);
                        String canonical = out.getCanonicalPath();
                        if (!canonical.startsWith(canonicalRoot) && !canonical.equals(packageDir.getCanonicalPath())) {
                            throw new SecurityException("entrada ZIP fora da pasta: " + name);
                        }
                        if (entry.isDirectory()) {
                            out.mkdirs();
                        }
                        else {
                            File parent = out.getParentFile();
                            if (parent != null) parent.mkdirs();
                            try (BufferedOutputStream bout = new BufferedOutputStream(new FileOutputStream(out))) {
                                int n;
                                while ((n = zin.read(buffer)) > 0) bout.write(buffer, 0, n);
                            }
                        }
                        zin.closeEntry();
                    }
                }

                File exe = new File(packageDir, "DLSS5ForAll.exe");
                if (!exe.isFile()) throw new Exception("DLSS5ForAll.exe não foi encontrado na raiz do ZIP");
                installBridgeBinary();
            }
            catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            final String result = error;
            runOnUiThread(() -> {
                importButton.setEnabled(true);
                if (result == null) {
                    enablePackageActions();
                    status.setText("Importação concluída. A cadeia original foi preservada e a ponte Android foi instalada ao lado dela.");
                }
                else {
                    status.setText("Falha ao importar: " + result);
                }
            });
        });
    }

    private void installBridgeBinary() throws Exception {
        if (!packageDir.isDirectory() && !packageDir.mkdirs()) throw new Exception("não foi possível criar a pasta do pacote");
        File out = new File(packageDir, BRIDGE_EXE);
        try (InputStream in = getAssets().open("android-frame-presenter.exe");
             BufferedOutputStream dst = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] b = new byte[128 * 1024];
            int n;
            while ((n = in.read(b)) > 0) dst.write(b, 0, n);
        }
        if (!out.isFile() || out.length() < 4096) throw new Exception("AndroidFramePresenter.exe inválido");
    }

    private void launchAndroidBridge() {
        if (container == null || packageDir == null) return;
        File exe = new File(packageDir, BRIDGE_EXE);
        if (!exe.isFile()) {
            Toast.makeText(this, "A ponte D3D11 não foi instalada.", Toast.LENGTH_LONG).show();
            return;
        }
        status.setText("Abrindo presenter D3D11. ReShade/DLSS5 do seu pacote será carregado no mesmo processo se for compatível com Wine/DXVK.");
        Intent i = new Intent(this, XServerDisplayActivity.class);
        i.putExtra("container_id", container.id);
        i.putExtra("exec_path", exe.getAbsolutePath());
        startActivity(i);
    }

    private void launchWindowsProgram() {
        if (container == null || packageDir == null) return;
        File exe = new File(packageDir, "DLSS5ForAll.exe");
        if (!exe.isFile()) {
            Toast.makeText(this, "Importe o ZIP primeiro.", Toast.LENGTH_LONG).show();
            return;
        }

        status.setText("Iniciando DLSS5ForAll.exe original em Wine/Box64…");
        Intent i = new Intent(this, XServerDisplayActivity.class);
        i.putExtra("container_id", container.id);
        i.putExtra("exec_path", exe.getAbsolutePath());
        startActivity(i);
    }

    private void showDiagnostics() {
        if (packageDir == null) return;
        StringBuilder text = new StringBuilder();
        text.append("===== COMPONENTES =====\n");
        String[] components = {
                "DLSS5ForAll.exe", BRIDGE_EXE, "dxgi.dll", "nvngx_dlss.dll", "nvngx_dlssnr.dll",
                "dlss5-feed.addon64", "renodx-dlss5.addon64", FRAME_FILE
        };
        for (String name : components) {
            File f = new File(packageDir, name);
            text.append(f.isFile() ? "[OK] " : "[--] ").append(name);
            if (f.isFile()) text.append("  ").append(f.length()).append(" bytes");
            text.append('\n');
        }

        File[] candidates = new File[]{
                new File(packageDir, "android-frame-presenter.log"),
                new File(packageDir, "ReShade.log"),
                new File(packageDir, "dlss5-feed.log"),
                new File(packageDir, "dlss5forall.log"),
                new File(packageDir, "bin/ReShade.log"),
                new File(packageDir, "bin/dlss5-feed.log"),
                new File(packageDir, "bin/dlss5forall.log")
        };
        for (File f : candidates) {
            if (!f.isFile()) continue;
            text.append("\n===== ").append(f.getName()).append(" =====\n");
            String s = FileUtils.readString(f);
            if (s != null) {
                int from = Math.max(0, s.length() - 16000);
                text.append(s.substring(from)).append('\n');
            }
        }

        text.append("\n===== NOTA DE COMPATIBILIDADE =====\n")
                .append("A captura Android e o presenter podem funcionar sem GPU NVIDIA. O backend NGX/DLSS Neural Rendering verdadeiro, porém, ainda depende de uma GPU/driver NVIDIA compatível. Este diagnóstico serve para localizar exatamente onde a cadeia para no Android.\n");

        Intent i = new Intent(this, DlssLogActivity.class);
        i.putExtra("log_text", text.toString());
        startActivity(i);
    }
}
