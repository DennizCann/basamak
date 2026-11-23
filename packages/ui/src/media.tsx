import { createContext, useContext, type ReactNode } from "react";

/**
 * Resolves an uploaded media id to a URL. The member app points at the public endpoint (served only while a
 * published version references the file); the CMS supplies short-lived signed URLs so drafts can be previewed.
 */
type Resolver = (id: string) => string | undefined;
const MediaContext = createContext<Resolver>((id) => `/api/public/media/${id}`);

export function MediaProvider({ resolve, children }: { resolve: Resolver; children: ReactNode }) {
  return <MediaContext.Provider value={resolve}>{children}</MediaContext.Provider>;
}

export function useMediaUrl(): Resolver {
  return useContext(MediaContext);
}
