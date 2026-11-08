use crate::proto_wire::{Reader, Writer};

/// CContentServerDirectory.GetCDNAuthToken#1 request (SteamKit unified
/// service): app/depot/host triple. Region-restricted CDN pools (e.g. the
/// Perfect World partner CDNs inside mainland China) reject tokenless
/// manifest/chunk requests with HTTP 403, so the downloader must fetch this.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientGetCdnAuthToken {
    pub app_id: u32,
    pub depot_id: u32,
    pub host_name: String,
}

impl CMsgClientGetCdnAuthToken {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        // Field numbers per SteamKit's CContentServerDirectory_GetCDNAuthToken_Request:
        // 1 = depot_id, 2 = host_name, 3 = app_id.
        w.uint32_field(1, self.depot_id);
        w.string_field(2, &self.host_name);
        w.uint32_field(3, self.app_id);
        out
    }
}

/// GetCDNAuthToken#1 response: the CDN auth token plus its expiry.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientGetCdnAuthTokenResponse {
    pub token: String,
    pub expiration_time: u32,
}

impl CMsgClientGetCdnAuthTokenResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match tag.field_number {
                1 => msg.token = reader.string()?,
                2 => msg.expiration_time = reader.u32()?,
                _ => {
                    if !reader.skip(tag.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(msg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_serializes_all_fields() {
        let req = CMsgClientGetCdnAuthToken {
            app_id: 2289720,
            depot_id: 2289721,
            host_name: "st.dl.eccdnx.com".into(),
        };
        let bytes = req.serialize();
        // Round-trip via manual parse: field1 = depot_id (varint),
        // field2 = host_name (string), field3 = app_id (varint).
        let mut r = Reader::new(&bytes);
        let mut depot = None;
        let mut host = None;
        let mut app = None;
        while let Some(tag) = r.next_tag() {
            match tag.field_number {
                1 => depot = Some(r.u32().unwrap()),
                2 => host = Some(r.string().unwrap()),
                3 => app = Some(r.u32().unwrap()),
                _ => panic!("unexpected field"),
            }
        }
        assert_eq!(depot, Some(2289721));
        assert_eq!(host.as_deref(), Some("st.dl.eccdnx.com"));
        assert_eq!(app, Some(2289720));
    }

    #[test]
    fn response_parses_token_fields() {
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.string_field(1, "cdn-token-abc");
            w.uint32_field(2, 1769000000);
        }
        let parsed = CMsgClientGetCdnAuthTokenResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.token, "cdn-token-abc");
        assert_eq!(parsed.expiration_time, 1769000000);
    }

    #[test]
    fn response_skips_unknown_fields() {
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(9, 3);
            w.string_field(1, "t");
        }
        let parsed = CMsgClientGetCdnAuthTokenResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.token, "t");
    }
}
