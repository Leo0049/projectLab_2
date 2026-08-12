/**
 * official-map-logic.js
 * Handles geolocation, coordinate storage, and distance calculations.
 */

const MapLogic = {
    // Haversine formula to calculate distance between two points in km
    calculateDistance: (lat1, lon1, lat2, lon2) => {
        if (!lat1 || !lon1 || !lat2 || !lon2) return null;

        const R = 6371; // Earth's radius in km
        const dLat = (lat2 - lat1) * Math.PI / 180;
        const dLon = (lon2 - lon1) * Math.PI / 180;
        const a =
            Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2);
        const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    },

    // Format distance for display
    formatDistance: (km) => {
        if (km === null) return '-- km';
        if (km < 1) {
            return `${Math.round(km * 1000)}m`;
        }
        return `${km.toFixed(1)}km`;
    },

    // Get user's current geolocation
    getUserLocation: () => {
        return new Promise((resolve, reject) => {
            if (!navigator.geolocation) {
                reject(new Error('Geolocation is not supported by your browser'));
                return;
            }

            navigator.geolocation.getCurrentPosition(
                (position) => {
                    const coords = {
                        lat: position.coords.latitude,
                        lng: position.coords.longitude
                    };
                    localStorage.setItem('userLat', coords.lat);
                    localStorage.setItem('userLng', coords.lng);
                    resolve(coords);
                },
                (error) => {
                    reject(error);
                },
                { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
            );
        });
    },

    /**
     * Geocode an address string to coordinates using Nominatim
     * @param {string} address Full address string
     * @param {object} components Optional {city, district, street} for structured fallback
     * @returns {Promise<{lat: number, lng: number, display_name: string} | null>}
     */
    geocode: async (address, components = null) => {
        if (!address) return null;

        // Normalization helper
        const normalize = (str) => str ? str.replace(/臺/g, '台') : '';

        const performSearch = async (query) => {
            if (!query) return null;
            try {
                const response = await fetch(`https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(query)}&limit=1&countrycodes=tw`, {
                    headers: { 'Accept-Language': 'zh-TW' }
                });
                const data = await response.json();
                return (data && data.length > 0) ? data[0] : null;
            } catch (err) {
                console.error('Geocode fetch failed:', err);
                return null;
            }
        };

        const performStructuredSearch = async (comps) => {
            try {
                const { city, district, street } = comps;
                const params = new URLSearchParams({
                    format: 'json',
                    limit: '1',
                    countrycodes: 'tw',
                    'accept-language': 'zh-TW',
                    street: normalize(street)
                });
                if (city) params.append('city', normalize(city));
                // In OSM, Taiwan districts are often 'county' or 'city_district'
                if (district) params.append('county', normalize(district));

                const response = await fetch(`https://nominatim.openstreetmap.org/search?${params.toString()}`);
                const data = await response.json();
                return (data && data.length > 0) ? data[0] : null;
            } catch (err) {
                console.error('Structured geocode fetch failed:', err);
                return null;
            }
        };

        try {
            const normalizedAddr = normalize(address);
            let result = null;

            // 1. Try structured search if components provided
            if (components && components.city && components.district && components.street) {
                result = await performStructuredSearch(components);
            }

            // 2. Try original and normalized concatenations
            if (!result) result = await performSearch(address);
            if (!result && normalizedAddr !== address) result = await performSearch(normalizedAddr);

            // 3. Try spaced search (City District Street)
            if (!result && components) {
                const spaced = `${normalize(components.city)} ${normalize(components.district)} ${normalize(components.street)}`;
                result = await performSearch(spaced);
            }

            // 4. Try broad fallback: Strip suffixes (市, 區, 號) and retry
            if (!result && components) {
                const cleanCity = normalize(components.city).replace(/[市縣]$/, '');
                const cleanDist = normalize(components.district).replace(/[區鄉鎮市]$/, '');
                const cleanStreet = normalize(components.street).replace(/號$/, '');
                
                // Try stripped version
                result = await performSearch(`${cleanCity} ${cleanDist} ${cleanStreet}`);
                
                // Try just Street + District if still nothing
                if (!result) result = await performSearch(`${cleanDist} ${cleanStreet}`);
                
                // Try Street without house number
                if (!result) result = await performSearch(`${cleanDist} ${cleanStreet.replace(/\d+$/, '')}`);
            }

            // 5. Very broad: Search just the street name in that area
            if (!result && components) {
                const streetMatch = normalize(components.street).match(/[^\d]+/);
                if (streetMatch) {
                    result = await performSearch(`${normalize(components.district)} ${streetMatch[0]}`);
                }
            }

            if (result) {
                return {
                    lat: parseFloat(result.lat),
                    lng: parseFloat(result.lon),
                    display_name: result.display_name
                };
            }
            return null;
        } catch (err) {
            console.error('Forward geocoding failed:', err);
            return null;
        }
    },

    /**
     * Reverse geocode coordinates to an address using Nominatim
     * @param {number} lat 
     * @param {number} lng 
     * @returns {Promise<string | null>}
     */
    reverseGeocode: async (lat, lng) => {
        try {
            const response = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lng}&zoom=18&addressdetails=1`, {
                headers: { 'Accept-Language': 'zh-TW' }
            });
            const data = await response.json();
            return data && data.display_name ? data.display_name : null;
        } catch (err) {
            console.error('Reverse geocoding failed:', err);
            return null;
        }
    },

    // Update distances for all store cards on the page
    updateStoreDistances: () => {
        const userLatVal = localStorage.getItem('userLat');
        const userLngVal = localStorage.getItem('userLng');

        if (!userLatVal || !userLngVal) {
            console.log('User coordinates not yet available for distance calculation');
            return;
        }

        const userLat = parseFloat(userLatVal);
        const userLng = parseFloat(userLngVal);

        if (isNaN(userLat) || isNaN(userLng)) return;

        const distanceElements = document.querySelectorAll('[data-store-lat][data-store-lng]');
        distanceElements.forEach(el => {
            const storeLat = parseFloat(el.getAttribute('data-store-lat'));
            const storeLng = parseFloat(el.getAttribute('data-store-lng'));

            // Skip if store coordinates are invalid (e.g. "undefined") — preserve backend-provided distance
            if (isNaN(storeLat) || isNaN(storeLng)) return;

            const dist = MapLogic.calculateDistance(userLat, userLng, storeLat, storeLng);
            const displayEl = el.querySelector('.store-distance-value') || el;
            displayEl.innerText = MapLogic.formatDistance(dist);
        });
    }
};

window.MapLogic = MapLogic;
