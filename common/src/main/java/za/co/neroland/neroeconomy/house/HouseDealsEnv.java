package za.co.neroland.neroeconomy.house;

import za.co.neroland.neroeconomy.obligation.AuctionTerms;

/**
 * What the Merchant Houses' auctions and jobs work with for one step: the Houses' own environment,
 * the auction terms, the settings, the mobs a bounty may be posted on, and where a delivery job
 * delivers to. The game builds one from its data packs and configuration ({@link Houses#dealsEnv});
 * a test builds its own.
 *
 * @param destination where a House's delivery jobs deliver to ({@code <dimension>|<x>|<y>|<z>}); empty
 *                    for none, and then the Houses post contracts only
 */
public record HouseDealsEnv(HouseEnv houses, AuctionTerms terms, HouseDealSettings settings, BountyTable bounties,
        String destination) {
    public HouseDealsEnv {
        if (houses == null) throw new IllegalArgumentException("house deals env");
        if (terms == null) terms = AuctionTerms.DEFAULTS;
        if (settings == null) settings = HouseDealSettings.DEFAULTS;
        if (bounties == null) bounties = BountyTable.EMPTY;
        if (destination == null) destination = "";
    }
}
