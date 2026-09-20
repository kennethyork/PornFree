/**
 * Lists that are not shipped with the app but are one tap away.
 *
 * The bundled list is deliberately the 156k-domain StevenBlack set: it filters well and keeps
 * memory use modest. The OISD lists are much larger (over 460k domains) and cost roughly
 * 50 MB of memory while loaded, so they are opt-in.
 */
export type SuggestedList = {
  id: string;
  title: string;
  description: string;
  url: string;
  heavy?: boolean;
};

export const SUGGESTED_LISTS: SuggestedList[] = [
  {
    id: 'oisd-nsfw-small',
    title: 'OISD NSFW (light)',
    description: 'A trimmed build of the OISD adult list. Good balance of size and coverage.',
    url: 'https://nsfw-small.oisd.nl/domainswild',
  },
  {
    id: 'oisd-nsfw',
    title: 'OISD NSFW (aggressive)',
    description: 'Over 460,000 domains, including many mirrors and obscure sites.',
    url: 'https://nsfw.oisd.nl/domainswild',
    heavy: true,
  },
];

export type ResolverPreset = {
  id: string;
  title: string;
  description: string;
  servers: string[];
};

/**
 * Blocking happens locally, but the resolver we relay allowed queries to matters too: the family
 * resolvers below filter adult content on their side, which catches domains no list knows about yet.
 */
export const RESOLVER_PRESETS: ResolverPreset[] = [
  {
    id: 'cleanbrowsing-family',
    title: 'CleanBrowsing Family',
    description: 'Blocks adult content and forces safe search on the major search engines.',
    servers: ['185.228.168.9', '185.228.169.9', '2a0d:2a00:1::2', '2a0d:2a00:2::2'],
  },
  {
    id: 'adguard-family',
    title: 'AdGuard Family',
    description: 'Blocks adult content plus ads and trackers.',
    servers: ['94.140.14.15', '94.140.15.16', '2a10:50c0::1:ff', '2a10:50c0::2:ff'],
  },
  {
    id: 'cloudflare-family',
    title: 'Cloudflare for Families',
    description: 'Blocks malware and adult content. Fast, but no safe search enforcement.',
    servers: ['1.1.1.3', '1.0.0.3', '2606:4700:4700::1113', '2606:4700:4700::1003'],
  },
  {
    id: 'cleanbrowsing-adult',
    title: 'CleanBrowsing Adult',
    description: 'Filters adult content only, and does not touch anything else.',
    servers: ['185.228.168.10', '185.228.169.11', '2a0d:2a00:1::', '2a0d:2a00:2::'],
  },
  {
    id: 'quad9',
    title: 'Quad9',
    description: 'Blocks malicious domains. No adult filtering upstream, so lists do all the work.',
    servers: ['9.9.9.9', '149.112.112.112', '2620:fe::fe', '2620:fe::9'],
  },
];

export function resolverPresetFor(servers: string[]): ResolverPreset | undefined {
  const key = [...servers].sort().join(',');
  return RESOLVER_PRESETS.find((preset) => [...preset.servers].sort().join(',') === key);
}
