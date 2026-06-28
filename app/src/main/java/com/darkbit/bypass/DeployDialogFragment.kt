package com.darkbit.bypass

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class DeployDialogFragment : DialogFragment() {

    private var currentStep = 1
    private val deployScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var deployJob: Job? = null

    // Callbacks
    var onDeploymentSuccess: (() -> Unit)? = null

    // Views
    private lateinit var tvTitle: TextView
    private lateinit var tvStepSub: TextView
    private lateinit var layoutStep1: View
    private lateinit var layoutStep2: View
    private lateinit var layoutStep3: View
    private lateinit var btnBack: View
    private lateinit var btnNext: com.google.android.material.button.MaterialButton
    private lateinit var btnClose: View
    private lateinit var tvConsoleLog: TextView
    private lateinit var scrollConsole: View
    private lateinit var pbProgress: View

    // Form inputs step 1
    private lateinit var etVpsIp: EditText
    private lateinit var etVpsPort: EditText
    private lateinit var etVpsUser: EditText
    private lateinit var etVpsPassword: EditText

    // Form inputs step 2
    private lateinit var etS3Endpoint: EditText
    private lateinit var etS3Region: EditText
    private lateinit var etS3Bucket: EditText
    private lateinit var etS3AccessKey: EditText
    private lateinit var etS3SecretKey: EditText
    private lateinit var etClientId: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make dialog take full screen while keeping the status bar
        setStyle(STYLE_NORMAL, R.style.Theme_Fedarisha_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_deploy_server, container, false)

        // Bind Views
        tvTitle = view.findViewById(R.id.tvDeployTitle)
        tvStepSub = view.findViewById(R.id.tvDeployStepSub)
        layoutStep1 = view.findViewById(R.id.layoutStep1)
        layoutStep2 = view.findViewById(R.id.layoutStep2)
        layoutStep3 = view.findViewById(R.id.layoutStep3)
        btnBack = view.findViewById(R.id.btnDeployBack)
        btnNext = view.findViewById(R.id.btnDeployNext)
        btnClose = view.findViewById(R.id.btnDeployClose)
        tvConsoleLog = view.findViewById(R.id.tvConsoleLog)
        scrollConsole = view.findViewById(R.id.scrollConsole)
        pbProgress = view.findViewById(R.id.pbDeployProgress)

        tvConsoleLog.movementMethod = ScrollingMovementMethod()

        // Inputs Step 1
        etVpsIp = view.findViewById(R.id.etVpsIp)
        etVpsPort = view.findViewById(R.id.etVpsPort)
        etVpsUser = view.findViewById(R.id.etVpsUser)
        etVpsPassword = view.findViewById(R.id.etVpsPassword)

        // Inputs Step 2
        etS3Endpoint = view.findViewById(R.id.etS3Endpoint)
        etS3Region = view.findViewById(R.id.etS3Region)
        etS3Bucket = view.findViewById(R.id.etS3Bucket)
        etS3AccessKey = view.findViewById(R.id.etS3AccessKey)
        etS3SecretKey = view.findViewById(R.id.etS3SecretKey)
        etClientId = view.findViewById(R.id.etClientId)

        // Load saved fields
        val prefs = context?.getSharedPreferences("deploy_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs != null) {
            etVpsIp.setText(prefs.getString("vps_ip", ""))
            etVpsPort.setText(prefs.getString("vps_port", "22"))
            etVpsUser.setText(prefs.getString("vps_user", "root"))
            etVpsPassword.setText(prefs.getString("vps_pass", ""))
            etS3Endpoint.setText(prefs.getString("s3_endpoint", ""))
            etS3Region.setText(prefs.getString("s3_region", ""))
            etS3Bucket.setText(prefs.getString("s3_bucket", ""))
            etS3AccessKey.setText(prefs.getString("s3_access", ""))
            etS3SecretKey.setText(prefs.getString("s3_secret", ""))
            etClientId.setText(prefs.getString("client_id", "4"))
        }

        // Setup Buttons
        btnClose.setOnClickListener {
            if (currentStep == 3 && deployJob?.isActive == true) {
                AlertDialogBuilderForCancel()
            } else {
                dismiss()
            }
        }

        btnBack.setOnClickListener {
            if (currentStep > 1) {
                currentStep--
                showStep(currentStep)
            }
        }

        btnNext.setOnClickListener {
            handleNext()
        }

        showStep(1)
        return view
    }

    private fun showStep(step: Int) {
        currentStep = step
        when (step) {
            1 -> {
                tvStepSub.text = "Шаг 1 из 3: Настройка VPS (SSH)"
                layoutStep1.visibility = View.VISIBLE
                layoutStep2.visibility = View.GONE
                layoutStep3.visibility = View.GONE
                btnBack.visibility = View.GONE
                btnNext.text = "Далее"
                btnClose.visibility = View.VISIBLE
            }
            2 -> {
                tvStepSub.text = "Шаг 2 из 3: Настройка S3 Хранилища"
                layoutStep1.visibility = View.GONE
                layoutStep2.visibility = View.VISIBLE
                layoutStep3.visibility = View.GONE
                btnBack.visibility = View.VISIBLE
                btnNext.text = "Начать установку"
                btnClose.visibility = View.VISIBLE
            }
            3 -> {
                tvStepSub.text = "Шаг 3 из 3: Прогресс установки"
                layoutStep1.visibility = View.GONE
                layoutStep2.visibility = View.GONE
                layoutStep3.visibility = View.VISIBLE
                btnBack.visibility = View.GONE
                btnNext.visibility = View.GONE
                btnClose.visibility = View.GONE // Prevent closing during install
            }
        }
    }

    private fun handleNext() {
        when (currentStep) {
            1 -> {
                if (validateStep1()) {
                    showStep(2)
                }
            }
            2 -> {
                if (validateStep2()) {
                    showStep(3)
                    startInstallation()
                }
            }
            3 -> {
                dismiss()
            }
        }
    }

    private fun validateStep1(): Boolean {
        val ip = etVpsIp.text.toString().trim()
        val port = etVpsPort.text.toString().trim()
        val user = etVpsUser.text.toString().trim()
        val pass = etVpsPassword.text.toString().trim()

        if (ip.isEmpty()) {
            Toast.makeText(context, "Введите IP-адрес сервера", Toast.LENGTH_SHORT).show()
            return false
        }
        if (port.isEmpty()) {
            Toast.makeText(context, "Введите порт SSH", Toast.LENGTH_SHORT).show()
            return false
        }
        if (user.isEmpty()) {
            Toast.makeText(context, "Введите имя пользователя SSH", Toast.LENGTH_SHORT).show()
            return false
        }
        if (pass.isEmpty()) {
            Toast.makeText(context, "Введите пароль SSH", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun validateStep2(): Boolean {
        val endpoint = etS3Endpoint.text.toString().trim()
        val region = etS3Region.text.toString().trim()
        val bucket = etS3Bucket.text.toString().trim()
        val accessKey = etS3AccessKey.text.toString().trim()
        val secretKey = etS3SecretKey.text.toString().trim()
        val client = etClientId.text.toString().trim()

        if (endpoint.isEmpty()) {
            Toast.makeText(context, "Введите S3 Endpoint", Toast.LENGTH_SHORT).show()
            return false
        }
        if (region.isEmpty()) {
            Toast.makeText(context, "Введите регион S3", Toast.LENGTH_SHORT).show()
            return false
        }
        if (bucket.isEmpty()) {
            Toast.makeText(context, "Введите имя бакета S3", Toast.LENGTH_SHORT).show()
            return false
        }
        if (accessKey.isEmpty()) {
            Toast.makeText(context, "Введите Access Key ID", Toast.LENGTH_SHORT).show()
            return false
        }
        if (secretKey.isEmpty()) {
            Toast.makeText(context, "Введите Secret Access Key", Toast.LENGTH_SHORT).show()
            return false
        }
        if (client.isEmpty()) {
            Toast.makeText(context, "Введите ID клиента", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun startInstallation() {
        val vpsIp = etVpsIp.text.toString().trim()
        val vpsPort = etVpsPort.text.toString().trim().toIntOrNull() ?: 22
        val vpsUser = etVpsUser.text.toString().trim()
        val vpsPass = etVpsPassword.text.toString().trim()

        val s3Endpoint = etS3Endpoint.text.toString().trim()
        val s3Region = etS3Region.text.toString().trim()
        val s3Bucket = etS3Bucket.text.toString().trim()
        val s3Access = etS3AccessKey.text.toString().trim()
        val s3Secret = etS3SecretKey.text.toString().trim()
        val clientId = etClientId.text.toString().trim()

        // Save fields to preferences
        context?.getSharedPreferences("deploy_prefs", android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("vps_ip", vpsIp)
            ?.putString("vps_port", vpsPort.toString())
            ?.putString("vps_user", vpsUser)
            ?.putString("vps_pass", vpsPass)
            ?.putString("s3_endpoint", s3Endpoint)
            ?.putString("s3_region", s3Region)
            ?.putString("s3_bucket", s3Bucket)
            ?.putString("s3_access", s3Access)
            ?.putString("s3_secret", s3Secret)
            ?.putString("client_id", clientId)
            ?.apply()

        tvConsoleLog.text = "Запуск развертывания...\nПодключение к $vpsIp:$vpsPort по SSH...\n"

        deployJob = deployScope.launch {
            var success = false
            withContext(Dispatchers.IO) {
                var session: Session? = null
                var channel: ChannelExec? = null
                try {
                    val jsch = JSch()
                    session = jsch.getSession(vpsUser, vpsIp, vpsPort)
                    session.setPassword(vpsPass)
                    session.setConfig("StrictHostKeyChecking", "no")
                    session.connect(15000) // 15 seconds timeout

                    updateLog("Успешно подключено по SSH!\nОтправка скрипта установки...")

                    val installScript = buildInstallScript(
                        clientId = clientId,
                        s3Bucket = s3Bucket,
                        s3Region = s3Region,
                        s3Endpoint = s3Endpoint,
                        s3AccessKey = s3Access,
                        s3SecretKey = s3Secret,
                        vpsIp = vpsIp
                    )

                    channel = session.openChannel("exec") as ChannelExec
                    // Combine stdout and stderr
                    val finalCommand = "cat << 'EOF' > /tmp/deploy.sh\n$installScript\nEOF\nbash /tmp/deploy.sh 2>&1"
                    channel.setCommand(finalCommand)
                    
                    val inStream = channel.inputStream
                    channel.connect()

                    val reader = BufferedReader(InputStreamReader(inStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.contains("=== DEPLOYMENT_SUCCESS ===")) {
                            success = true
                        }
                        updateLog(line!!)
                    }

                    // Check exit status
                    while (!channel.isClosed) {
                        delay(200)
                    }
                    val exitStatus = channel.exitStatus
                    if (exitStatus != 0) {
                        updateLog("\nОшибка: Скрипт установки завершился с кодом $exitStatus")
                        success = false
                    }

                } catch (e: Exception) {
                    updateLog("\nСбой установки: ${e.localizedMessage ?: e.message}")
                    success = false
                } finally {
                    try {
                        channel?.disconnect()
                    } catch (e: Exception) {}
                    try {
                        session?.disconnect()
                    } catch (e: Exception) {}
                }
            }

            // Finish UI updates on Main
            pbProgress.visibility = View.GONE
            btnClose.visibility = View.VISIBLE
            btnNext.visibility = View.VISIBLE

            if (success) {
                updateLog("\n=== РАЗВЕРТЫВАНИЕ ЗАВЕРШЕНО УСПЕШНО! ===")
                btnNext.text = "Готово"
                createClientProfile(
                    s3Endpoint = s3Endpoint,
                    s3Region = s3Region,
                    s3Bucket = s3Bucket,
                    s3Access = s3Access,
                    s3Secret = s3Secret,
                    clientId = clientId
                )
                onDeploymentSuccess?.invoke()
            } else {
                updateLog("\n=== РАЗВЕРТЫВАНИЕ ЗАВЕРШИЛОСЬ ОШИБКОЙ! ===")
                btnNext.text = "Закрыть"
            }
        }
    }

    private suspend fun updateLog(message: String) {
        withContext(Dispatchers.Main) {
            tvConsoleLog.append("$message\n")
            // Auto scroll console to bottom
            scrollConsole.post {
                scrollConsole.scrollTo(0, tvConsoleLog.bottom)
            }
        }
    }

    private fun createClientProfile(
        s3Endpoint: String,
        s3Region: String,
        s3Bucket: String,
        s3Access: String,
        s3Secret: String,
        clientId: String
    ) {
        val s3Domain = try {
            val uri = java.net.URI(s3Endpoint)
            uri.host ?: s3Endpoint.replace("https://", "").replace("http://", "").split("/")[0]
        } catch (e: Exception) {
            s3Endpoint.replace("https://", "").replace("http://", "").split("/")[0]
        }

        val clientConfig = """
        {
          "log": {
            "loglevel": "info"
          },
          "dns": {
            "servers": [
              "8.8.8.8",
              "1.1.1.1"
            ]
          },
          "inbounds": [
            {
              "tag": "socks-in",
              "listen": "127.0.0.1",
              "port": 10808,
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              }
            },
            {
              "protocol": "tun",
              "settings": {
                "mtu": 1500,
                "name": "xray0",
                "userLevel": 8
              },
              "sniffing": {
                "destOverride": [
                  "http",
                  "tls",
                  "quic"
                ],
                "enabled": true
              },
              "tag": "tun"
            }
          ],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "fedarisha",
              "settings": {
                "storage": {
                  "type": "s3",
                  "bucket": "$s3Bucket",
                  "prefix": "fedarisha/$clientId/",
                  "sessionsDir": "sessions",
                  "region": "$s3Region",
                  "endpoint": "$s3Endpoint",
                  "accessKey": "$s3Access",
                  "secretKey": "$s3Secret"
                },
                "tuning": {
                  "idleTimeoutSec": 3600,
                  "pollIntervalMs": 100,
                  "writeIntervalMs": 80,
                  "maxFileSizeBytes": 16777216
                }
              }
            },
            {
              "tag": "direct",
              "protocol": "freedom",
              "settings": {}
            }
          ],
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
              {
                "type": "field",
                "domain": [
                  "$s3Domain"
                ],
                "outboundTag": "direct"
              }
            ]
          }
        }
        """.trimIndent()

        try {
            val configsDir = File(context?.filesDir, "configs")
            if (!configsDir.exists()) {
                configsDir.mkdirs()
            }
            val fileName = "VPS_${s3Bucket}.json"
            val file = File(configsDir, fileName)
            file.writeText(clientConfig)
            
            // Save active config preference
            context?.getSharedPreferences("vpn_prefs", android.content.Context.MODE_PRIVATE)
                ?.edit()
                ?.putString("active_config", fileName)
                ?.apply()

            Toast.makeText(context, "Профиль $fileName успешно добавлен и выбран!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка сохранения профиля: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildInstallScript(
        clientId: String,
        s3Bucket: String,
        s3Region: String,
        s3Endpoint: String,
        s3AccessKey: String,
        s3SecretKey: String,
        vpsIp: String
    ): String {
        return """
        #!/bin/bash
        set -e

        # Redirect standard error to standard output
        exec 2>&1

        echo "=== [1/6] Проверка и настройка SWAP-файла (2GB) ==="
        if [ ! -f /swapfile ]; then
            echo "SWAP не найден. Создаем /swapfile для предотвращения сбоев компиляции..."
            fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048
            chmod 600 /swapfile
            mkswap /swapfile
            swapon /swapfile
            echo '/swapfile none swap sw 0 0' >> /etc/fstab
            echo "SWAP успешно подключен."
        else
            echo "SWAP-файл уже существует."
        fi

        echo "=== [2/6] Обновление менеджера пакетов и установка зависимостей ==="
        apt-get update -y
        apt-get install -y git wget curl build-essential

        echo "=== [3/6] Установка Go 1.26.4 ==="
        need_install=true
        if command -v go &> /dev/null; then
            current_ver=$(go version | awk '{print $3}' | sed 's/go//')
            echo "Найден установленный Go: go${'$'}current_ver"
            if echo "${'$'}current_ver" | grep -qE "^1\.(2[6-9]|[3-9][0-9])"; then
                echo "Версия Go достаточно новая (>= 1.26.0)."
                need_install=false
            else
                echo "Версия Go устарела (нужна >= 1.26.0)."
            fi
        fi

        if [ "${'$'}need_install" = "true" ]; then
            echo "Скачиваем и устанавливаем Go 1.26.4..."
            wget -q https://go.dev/dl/go1.26.4.linux-amd64.tar.gz
            rm -rf /usr/local/go
            tar -C /usr/local -xzf go1.26.4.linux-amd64.tar.gz
            rm go1.26.4.linux-amd64.tar.gz
        fi

        export PATH=/usr/local/go/bin:${'$'}PATH
        if ! echo "${'$'}PATH" | grep -q "/usr/local/go/bin"; then
            echo 'export PATH=/usr/local/go/bin:${'$'}PATH' >> ~/.bashrc
        fi
        echo "Используется Go: $(go version)"

        echo "=== [4/6] Скачивание и сборка Xray Fedarisha ==="
        rm -rf /tmp/xray-build
        echo "Клонирование репозитория Xray-core-fedarisha..."
        git clone https://github.com/Fedarisha/Xray-core-fedarisha.git /tmp/xray-build
        cd /tmp/xray-build/main
        echo "Запуск компиляции (это может занять 1-3 минуты)..."
        go build -o /usr/local/bin/xray-fedarisha -trimpath -ldflags "-s -w"
        chmod +x /usr/local/bin/xray-fedarisha
        echo "Ядро Xray успешно скомпилировано."

        echo "=== [5/6] Создание конфигурации сервера ==="
        mkdir -p /usr/local/etc/xray-fedarisha/

        cat << 'EOF_CONFIG' > /usr/local/etc/xray-fedarisha/config.json
        {
          "log": {
            "loglevel": "info"
          },
          "inbounds": [
            {
              "tag": "fedarisha-in",
              "protocol": "fedarisha",
              "settings": {
                "tuning": {
                  "idleTimeoutSec": 300,
                  "pollIntervalMs": 100,
                  "writeIntervalMs": 20,
                  "maxFileSizeBytes": 2097152
                },
                "clients": [
                  {
                    "id": "$clientId",
                    "email": "user-$clientId",
                    "level": 0
                  }
                ],
                "storage": {
                  "type": "s3",
                  "bucket": "$s3Bucket",
                  "prefix": "fedarisha/",
                  "sessionsDir": "sessions",
                  "region": "$s3Region",
                  "endpoint": "$s3Endpoint",
                  "accessKey": "$s3AccessKey",
                  "secretKey": "$s3SecretKey"
                }
              }
            }
          ],
          "outbounds": [
            {
              "tag": "direct",
              "protocol": "freedom",
              "settings": {}
            }
          ]
        }
        EOF_CONFIG
        echo "Файл config.json сохранен."

        echo "=== [6/6] Создание и запуск системной службы Systemd ==="
        cat << 'EOF_SERVICE' > /etc/systemd/system/xray-fedarisha.service
        [Unit]
        Description=Xray Fedarisha Service
        After=network.target nss-lookup.target

        [Service]
        Type=simple
        User=root
        ExecStart=/usr/local/bin/xray-fedarisha -config /usr/local/etc/xray-fedarisha/config.json
        Restart=on-failure
        RestartSec=5
        LimitNPROC=10000
        LimitNOFILE=1000000

        [Install]
        WantedBy=multi-user.target
        EOF_SERVICE

        echo "Перезапуск Systemd и старт xray-fedarisha..."
        systemctl daemon-reload
        systemctl enable xray-fedarisha
        systemctl restart xray-fedarisha

        echo "Проверка статуса службы..."
        sleep 2
        if systemctl is-active --quiet xray-fedarisha; then
            echo "Служба xray-fedarisha успешно запущена и работает."
            echo "=== DEPLOYMENT_SUCCESS ==="
        else
            echo "Ошибка: Служба xray-fedarisha не смогла запуститься."
            systemctl status xray-fedarisha
            exit 1
        fi
        """.trimIndent()
    }

    private fun AlertDialogBuilderForCancel() {
        context?.let { ctx ->
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Прерывание установки")
                .setMessage("Процесс установки сейчас запущен. Вы действительно хотите прервать деплой и закрыть окно?")
                .setPositiveButton("Прервать") { _, _ ->
                    deployJob?.cancel()
                    dismiss()
                }
                .setNegativeButton("Продолжить", null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        deployScope.cancel()
    }
}
