use bytes::Bytes;
use tonic::metadata::{
    AsciiMetadataKey, AsciiMetadataValue, BinaryMetadataKey, BinaryMetadataValue, KeyAndValueRef,
    MetadataMap,
};

use crate::{Error, Result};

/// Ordered gRPC metadata. Keys ending in `-bin` carry raw binary values; other values are
/// printable ASCII. Base64 for `-bin` values is applied on the wire by tonic, never here.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Metadata(pub Vec<(String, Bytes)>);

impl Metadata {
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    pub fn iter(&self) -> impl Iterator<Item = (&str, &Bytes)> {
        self.0.iter().map(|(key, value)| (key.as_str(), value))
    }

    pub(crate) fn append_to(&self, map: &mut MetadataMap) -> Result<()> {
        for (key, value) in self.iter() {
            if key.ends_with("-bin") {
                let key = BinaryMetadataKey::from_bytes(key.as_bytes())
                    .map_err(|_| Error::invalid_argument(format!("metadata key {key:?}")))?;
                map.append_bin(key, BinaryMetadataValue::from_bytes(value));
            } else {
                let key = AsciiMetadataKey::from_bytes(key.as_bytes())
                    .map_err(|_| Error::invalid_argument(format!("metadata key {key:?}")))?;
                let value = AsciiMetadataValue::try_from(value.as_ref()).map_err(|_| {
                    Error::invalid_argument(format!("metadata value of {key:?} is not ASCII"))
                })?;
                map.append(key, value);
            }
        }
        Ok(())
    }
}

impl From<&MetadataMap> for Metadata {
    fn from(map: &MetadataMap) -> Self {
        let entries = map
            .iter()
            .filter_map(|entry| match entry {
                KeyAndValueRef::Ascii(key, value) => Some((
                    key.as_str().to_owned(),
                    Bytes::copy_from_slice(value.as_encoded_bytes()),
                )),
                // A peer sending invalid base64 is not worth failing the call over.
                KeyAndValueRef::Binary(key, value) => value
                    .to_bytes()
                    .ok()
                    .map(|value| (key.as_str().to_owned(), value)),
            })
            .collect();
        Metadata(entries)
    }
}

impl<K: Into<String>, V: Into<Bytes>> FromIterator<(K, V)> for Metadata {
    fn from_iter<I: IntoIterator<Item = (K, V)>>(iter: I) -> Self {
        Metadata(
            iter.into_iter()
                .map(|(key, value)| (key.into(), value.into()))
                .collect(),
        )
    }
}
