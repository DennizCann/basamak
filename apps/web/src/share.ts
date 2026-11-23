import { Directory, Filesystem } from "@capacitor/filesystem";
import { Share } from "@capacitor/share";
import { api } from "./auth";

/**
 * Mobile: an <a download> does nothing inside the WebView. The PDF is fetched with the session, written to the
 * app's cache directory (private to the app, cleaned by the OS) and handed to the system share sheet, from which
 * the member can save it to Files/Drive or send it.
 */
export async function sharePdf(path: string, filename: string) {
  const blob = await api.blob(path);
  const data = await new Promise<string>((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(",")[1] ?? "");
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
  const saved = await Filesystem.writeFile({ path: filename, data, directory: Directory.Cache });
  await Share.share({ title: filename, url: saved.uri, dialogTitle: "Sertifikayı paylaş" });
}
