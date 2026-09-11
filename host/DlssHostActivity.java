package com.winlator;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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
 * The APK intentionally does not bundle NVIDIA/DLSS proprietary binaries.
 * The user imports their own ZIP at runtime; Winlator provides Wine/Box64/DXVK.
 */
public final class DlssHostActivity extends AppCompatActivity {
    private static final int REQ_PACKAGE = 5010;
    private static final String CONTAINER_NAME = "Android DLSS Host";
    private static final String INSTALL_DIR = "DLSS5ForAll";

    private final Handler handler = new Handler();
    private TextView status;
    private Button importButton;
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
        root.setPadding(42, 42, 42, 42);
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
        info.setText("Host experimental do DLSS5ForAll para Android.\n\n" +
                "Esta versão preserva o executável Windows e a lógica original dentro de Wine/Box64. " +
                "Por motivos de licença, o APK não inclui nvngx_dlssnr.dll, ReShade nem outros binários do pacote: " +
                "selecione o ZIP DLSS5ForAll-Portable que você já possui.\n\n" +
                "Primeiro objetivo: executar a cadeia original e registrar exatamente o que funciona e onde o backend NVIDIA para no Android.");
        info.setTextColor(Color.LTGRAY);
        info.setTextSize(16f);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(0, 28, 0, 28);
        root.addView(info, ip);

        importButton = new Button(this);
        importButton.setText("IMPORTAR DLSS5FORALL-PORTABLE.ZIP");
        importButton.setEnabled(false);
        importButton.setOnClickListener(v -> choosePackage());
        root.addView(importButton, new LinearLayout.LayoutParams(-1, -2));

        launchButton = new Button(this);
        launchButton.setText("EXECUTAR PROGRAMA WINDOWS");
        launchButton.setEnabled(false);
        launchButton.setOnClickListener(v -> launchWindowsProgram());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 14, 0, 0);
        root.addView(launchButton, lp);

        diagnosticsButton = new Button(this);
        diagnosticsButton.setText("VER DIAGNÓSTICO");
        diagnosticsButton.setEnabled(false);
        diagnosticsButton.setOnClickListener(v -> showDiagnostics());
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.setMargins(0, 14, 0, 0);
        root.addView(diagnosticsButton, dp);

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
        launchButton.setEnabled(exe.isFile());
        diagnosticsButton.setEnabled(packageDir.isDirectory());
        status.setText(exe.isFile()
                ? "Pacote encontrado. Pronto para executar a versão Windows."
                : "Runtime pronto. Agora selecione o ZIP original do DLSS5ForAll.");
    }

    private void choosePackage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "application/x-zip-compressed"});
        startActivityForResult(i, REQ_PACKAGE);
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
        }
    }

    private void importPackage(Uri uri) {
        if (container == null) return;
        importButton.setEnabled(false);
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
            }
            catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            final String result = error;
            runOnUiThread(() -> {
                importButton.setEnabled(true);
                if (result == null) {
                    launchButton.setEnabled(true);
                    diagnosticsButton.setEnabled(true);
                    status.setText("Importação concluída. O programa Windows está pronto para o primeiro teste.");
                }
                else {
                    status.setText("Falha ao importar: " + result);
                }
            });
        });
    }

    private void launchWindowsProgram() {
        if (container == null || packageDir == null) return;
        File exe = new File(packageDir, "DLSS5ForAll.exe");
        if (!exe.isFile()) {
            Toast.makeText(this, "Importe o ZIP primeiro.", Toast.LENGTH_LONG).show();
            return;
        }

        status.setText("Iniciando DLSS5ForAll.exe em Wine/Box64…");
        Intent i = new Intent(this, XServerDisplayActivity.class);
        i.putExtra("container_id", container.id);
        i.putExtra("exec_path", exe.getAbsolutePath());
        startActivity(i);
    }

    private void showDiagnostics() {
        if (packageDir == null) return;
        File[] candidates = new File[]{
                new File(packageDir, "bin/dlss5forall.log"),
                new File(packageDir, "bin/ReShade.log"),
                new File(packageDir, "bin/dlss5-feed.log"),
                new File(packageDir, "dlss5forall.log")
        };
        StringBuilder text = new StringBuilder();
        for (File f : candidates) {
            if (!f.isFile()) continue;
            text.append("\n===== ").append(f.getName()).append(" =====\n");
            String s = FileUtils.readString(f);
            if (s != null) {
                int from = Math.max(0, s.length() - 12000);
                text.append(s.substring(from)).append('\n');
            }
        }
        if (text.length() == 0) text.append("Ainda não há logs do programa. Execute-o pelo menos uma vez.");

        Intent i = new Intent(this, DlssLogActivity.class);
        i.putExtra("log_text", text.toString());
        startActivity(i);
    }
}
