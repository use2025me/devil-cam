# Devil Cam on Alexa / Echo Show: setup guide

When this is done you can say **"Alexa, show Camera 1"** and see it on an Echo Show or Fire TV.

How it works:

```
Hikvision recorder ──> Devil Cam Hub (Raspberry Pi at home) ──> secure Cloudflare address ──> Echo Show
                                                                       ▲
                                     "Alexa, show Camera 1" ──> Devil Cam skill (AWS Lambda)
```

No ports are opened on your router. The camera feed is protected by a secret key, and the skill only works for the Amazon account(s) you allow.

---

## Part A: free accounts (do these any time)

### 1. Amazon developer account
1. Go to **developer.amazon.com** and sign in with the **same Amazon account your Echo Show uses**.
2. Fill in the developer profile (name, address). It's free.

### 2. AWS account
1. Go to **aws.amazon.com** and click **Create an AWS account**.
2. It asks for a card. Devil Cam stays well inside the free allowance.
3. When you're in, set the region (top right) to **Europe (Ireland) eu-west-1**. Alexa skills in Europe must run there.

---

## Part B: the hub (when the Raspberry Pi arrives)

### 3. Cloudflare tunnel (the secure address)
1. Go to **dash.cloudflare.com** → **Zero Trust** → **Networks** → **Tunnels** → **Create a tunnel**.
2. Choose **Cloudflared**, name it `devil-cam-hub`, and click **Save**.
3. On the next page Cloudflare shows an install command with a long **token** (the part after `--token` or `install`). **Copy just the token** and keep it for step 4.
4. Click **Next** to **Public hostname**:
   - **Subdomain:** `cam`
   - **Domain:** `devilmediagroup.com`
   - **Service type:** `HTTP`
   - **URL:** `localhost:8080`
5. Save. Your hub's address will be **https://cam.devilmediagroup.com**.

### 4. Set up the Raspberry Pi
1. Plug the Pi into your **router with an Ethernet cable**, then plug in its power.
2. If the memory card didn't come with Raspberry Pi OS already on it, use **Raspberry Pi Imager** on your laptop (raspberrypi.com/software). Choose **Raspberry Pi OS Lite (64-bit)**, and in the settings set hostname `devilcam`, turn on **SSH**, and pick a username and password.
3. On your laptop (same Wi-Fi), open **Command Prompt** and type:
   ```
   ssh yourusername@devilcam.local
   ```
   Answer `yes` and enter the Pi's password.
4. Paste this one line and press Enter:
   ```
   curl -fsSL https://raw.githubusercontent.com/use2025me/devil-cam/main/hub/install.sh | sudo bash
   ```
5. It asks for:
   - **Recorder address:** `192.168.68.57`
   - **Recorder username:** `devilcam`
   - **Recorder password:** the devilcam password
   - **Which cameras:** `1` (add more numbers later if you connect more cameras, e.g. `1 2 3 4`)
   - **Cloudflare tunnel token:** from step 3
6. At the end it prints **HUB_SECRET**. Copy it somewhere safe for step 6.

---

## Part C: the Alexa skill

### 5. Login with Amazon (lets Alexa link the skill to your account)
1. In **developer.amazon.com**, open **Login with Amazon** → **Create a New Security Profile**.
2. Name: `Devil Cam`, description: `Devil Cam Alexa skill`, privacy URL: `https://devilmediagroup.com`.
3. Open the profile's **Web Settings** and note the **Client ID** and **Client Secret**.

### 6. The skill's code (AWS Lambda)
1. In the **AWS console** (region **Europe (Ireland)**), open **Lambda** → **Create function**.
   - Name: `devil-cam-alexa`
   - Runtime: **Node.js 20.x**
2. In the code editor, delete the sample, create a file called **`index.mjs`**, paste in the code from
   `https://github.com/use2025me/devil-cam/blob/main/alexa/index.mjs`, then click **Deploy**.
3. **Configuration** → **Environment variables** → **Edit** → add:
   - `HUB_URL` = `https://cam.devilmediagroup.com`
   - `HUB_SECRET` = the key from step 4
   - `ALLOWED_EMAILS` = the email of your Amazon account (add more, separated by commas, for family)
4. **Configuration** → **General configuration** → set **Timeout** to **8 seconds**.
5. Copy the function's **ARN** (top right, starts with `arn:aws:lambda:eu-west-1:`).

### 7. Create the skill
1. Go to **developer.amazon.com/alexa/console/ask** → **Create Skill**.
   - Name: `Devil Cam`
   - Locale: **English (UK)** (add **English (IE)** too if it's offered)
   - Type: **Smart Home**
2. Copy the **Skill ID** it shows. Back in Lambda, click **Add trigger** → **Alexa** → **Alexa Smart Home**, paste the Skill ID, and click **Add**.
3. In the skill, under **Smart Home service endpoint**, paste the Lambda ARN into **Default endpoint** and **Europe**. Save.
4. **Account Linking** (left menu):
   - Authorization grant type: **Auth Code Grant**
   - Web Authorization URI: `https://www.amazon.com/ap/oa`
   - Access Token URI: `https://api.amazon.com/auth/o2/token`
   - Client ID / Client Secret: from step 5
   - Client Authentication Scheme: **HTTP Basic**
   - Scope: `profile`
   - Save. Copy the **Alexa Redirect URLs** shown at the bottom.
5. Back in your **Login with Amazon** security profile → **Web Settings** → **Edit**, and paste each Alexa Redirect URL into **Allowed Return URLs**. Save.

### 8. Turn it on
1. On your phone, open the **Alexa app** → **More** → **Skills & Games** → **Your Skills** → **Dev**.
2. Tap **Devil Cam** → **Enable to use**, and sign in with your Amazon account.
3. Tap **Discover devices**. **Camera 1** appears.
4. Say: **"Alexa, show Camera 1."**

---

## If something doesn't work
- **"Camera 1" doesn't appear:** check the hub is on, and that `https://cam.devilmediagroup.com/<HUB_SECRET>/api/streams` opens in a browser and lists your cameras.
- **Echo Show says the camera isn't responding:** the hub may be slow to start the video. Try again; the second time is usually faster.
- **Can't link the skill:** check `ALLOWED_EMAILS` matches your Amazon account email exactly.

Your skill stays private to your account (it's a "development" skill), so nobody else can find or use it.
