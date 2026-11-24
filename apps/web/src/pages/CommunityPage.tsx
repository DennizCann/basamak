import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { StateView, errorMessage, fmt, trCompare } from "@basamak/ui";
import { api } from "../auth";

type FeedItem = { id: number; type: string; objectType: string; objectId: string; objectTitle: string | null; at: string; userId: string; userName: string };
type SharedTool = { id: string; name: string; ownerId: string; ownerName: string; forkCount: number; description?: string; method?: string; host?: string; needsSecrets?: boolean };
type Shared = { id: string; name: string; description: string; ownerId: string; ownerName: string; forkCount: number };
type Person = { id: string; displayName: string; bio: string | null; score?: number; shared?: number };

const VERB: Record<string, string> = {
  TRAINING_COMPLETED: "bir eğitimi tamamladı", JOURNEY_COMPLETED: "bir Journey'i tamamladı ve seviye atladı", ITEM_SHARED: "bir iş akışı paylaştı",
  QUIZ_PASSED: "bir quiz'i geçti", WORKFLOW_CREATED: "yeni bir iş akışı kurdu",
};

function linkFor(i: FeedItem) {
  if (i.objectType === "TRAINING") return `/trainings/${i.objectId}`;
  if (i.objectType === "WORKFLOW" && i.objectTitle) return `/workflows/${i.objectId}`;
  return null;
}

/** Community home: activity of followed members (keyset paging) and the shared workflows to discover people. */
export function CommunityPage() {
  const [feed, setFeed] = useState<FeedItem[] | null>(null);
  const [more, setMore] = useState(true);
  const [shared, setShared] = useState<Shared[]>([]);
  const [tools, setTools] = useState<SharedTool[]>([]);
  const [people, setPeople] = useState<Person[]>([]);
  const [following, setFollowing] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (before?: number) => {
    try {
      const page = await api.get<FeedItem[]>(`/api/feed?limit=20${before ? `&before=${before}` : ""}`);
      setFeed((f) => (before && f ? [...f, ...page] : page));
      setMore(page.length === 20);
    } catch (e) { setError(errorMessage(e)); }
  }, []);
  useEffect(() => {
    load();
    api.get<Shared[]>("/api/community/workflows").then(setShared).catch(() => {});
    api.get<SharedTool[]>("/api/community/tools").then(setTools).catch(() => {});
    api.get<Person[]>("/api/community/people?limit=5").then(setPeople).catch(() => {});
  }, [load]);

  async function follow(p: Person) {
    setFollowing(p.id);
    try {
      await api.post(`/api/profiles/${p.id}/follow`);
      setPeople((list) => list.filter((x) => x.id !== p.id));
      setNotice(`${p.displayName} takip ediliyor. Paylaşımları akışında görünecek.`);
      await load();
    } catch (e) { setError(errorMessage(e)); }
    finally { setFollowing(null); }
  }

  return (
    <main className="page stack">
      <div className="row space-between">
        <h1>Topluluk</h1>
        <Link className="btn" to="/profile">Profilim</Link>
      </div>
      {notice && <p role="status" className="alert alert-success">{notice}</p>}
      <section className="card stack" aria-labelledby="feed-h">
        <h2 id="feed-h">Takip ettiklerin</h2>
        <StateView loading={!feed && !error} error={error} onRetry={() => load()} empty={feed?.length === 0}
                   emptyText={people.length > 0 ? "Akışın boş. Aşağıdan birkaç üyeyi takip et; paylaştıkları ve bitirdikleri burada görünür."
                     : "Akışın boş. Profilini herkese açan üyeleri takip edebilirsin; paylaşılan iş akışlarındaki isimlere tıklayarak profillerine gidebilirsin."}>
          <ol className="card-list" aria-label="Etkinlik akışı">
            {feed?.map((i) => {
              const href = linkFor(i);
              return (
                <li key={i.id}>
                  <Link className="text-link" to={`/u/${i.userId}`}>{i.userName}</Link> {i.type === "ITEM_SHARED" && i.objectType === "TOOL" ? "bir araç paylaştı" : VERB[i.type] ?? i.type}
                  {i.objectTitle && <>: {href ? <Link className="text-link" to={href}>{i.objectTitle}</Link> : <strong>{i.objectTitle}</strong>}</>}
                  <div className="hint-line">{fmt.dateTime(i.at)}</div>
                </li>
              );
            })}
          </ol>
          {feed && feed.length > 0 && more && <button className="btn" onClick={() => load(feed[feed.length - 1].id)}>Daha eski etkinlikler</button>}
        </StateView>
      </section>
      {people.length > 0 && (
        <section className="card stack" aria-labelledby="people-h">
          <h2 id="people-h">Kimi takip edebilirsin?</h2>
          <ul className="card-list">
            {people.map((p) => (
              <li key={p.id} className="row space-between">
                <span>
                  <Link className="text-link" to={`/u/${p.id}`}><strong>{p.displayName}</strong></Link>
                  {(p.score !== undefined || p.shared !== undefined) && <span className="muted"> · {[p.score !== undefined ? `${fmt.num(p.score)} puan` : null, p.shared ? `${fmt.num(p.shared)} paylaşım` : null].filter(Boolean).join(" · ")}</span>}
                  {p.bio && <div className="hint-line">{p.bio}</div>}
                </span>
                <button className="btn btn-sm" disabled={following === p.id} onClick={() => follow(p)}
                        aria-label={`Takip et: ${p.displayName}`}>{following === p.id ? "Takip ediliyor…" : "Takip et"}</button>
              </li>
            ))}
          </ul>
          <p className="hint-line">En çok puan toplayanları <Link className="text-link" to="/leaderboard">Sıralama</Link> sayfasında da görebilirsin.</p>
        </section>
      )}
      <section className="card stack" aria-labelledby="tools-h">
        <h2 id="tools-h">Paylaşılan araçlar</h2>
        {tools.length === 0 ? <p className="muted">Henüz paylaşılan araç yok. <Link className="text-link" to="/tools">Araçlarım</Link> sayfasından kendi aracını kurup toplulukla paylaşabilirsin.</p> : (
          <ul className="card-list">
            {tools.slice(0, 20).map((t) => (
              <li key={t.id} className="row space-between">
                <span><strong>{t.name}</strong> <span className="muted">· <Link className="text-link" to={`/u/${t.ownerId}`}>{t.ownerName}</Link> · {t.method} {t.host} · {fmt.num(t.forkCount)} çatal{t.needsSecrets ? " · kendi API anahtarını eklemen gerekir" : ""}</span></span>
                <button className="btn btn-sm" onClick={async () => {
                  try { await api.post(`/api/community/tools/${t.id}/fork`); setNotice(`"${t.name}" araçlarına kopyalandı. Araçlarım sayfasından çalıştırabilirsin.`); }
                  catch (e) { setError(errorMessage(e)); }
                }}>Araçlarıma kopyala</button>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="card stack" aria-labelledby="disc-h">
        <h2 id="disc-h">Paylaşılan iş akışları</h2>
        <p className="hint-line">Bir akışı açıp çalıştırabilir, puan verebilir, yorum yazabilir ya da kendi listene çatallayabilirsin. Eğitimlerle ilgili yorumlar her eğitim sayfasının altındaki <strong>Tartışma</strong> bölümündedir.</p>
        {shared.length === 0 ? <p className="muted">Henüz paylaşılan iş akışı yok. <Link className="text-link" to="/workflows">İş akışları</Link> sayfasından kendi akışını kurup paylaşabilirsin.</p> : (
          <ul className="card-list">
            {[...shared].sort((a, b) => b.forkCount - a.forkCount || trCompare(a.name, b.name)).slice(0, 20).map((w) => (
              <li key={w.id} className="row space-between">
                <span><Link className="text-link" to={`/workflows/${w.id}`}>{w.name}</Link> <span className="muted">· <Link className="text-link" to={`/u/${w.ownerId}`}>{w.ownerName}</Link></span></span>
                <span className="muted">{fmt.num(w.forkCount)} çatal</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
